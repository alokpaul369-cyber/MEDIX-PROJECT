package medix.service;

import java.sql.SQLException;
import java.util.Calendar;
import java.util.Date;
import medix.dao.InventoryDao;
import medix.dao.PharmacyDao;
import medix.dao.ReportDao;
import medix.dao.ReservationDao;
import medix.engine.SearchEngine;
import medix.model.CancelReason;
import medix.model.NotificationType;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.ReservationStatus;
import medix.util.DateUtil;
import medix.util.ErrorLog;

/**
 * Pharmacy trust score (the report's "pharmacy trust score" mitigation), saved in
 * pharmacies.trust_score and shown in search results:
 *
 * <pre>
 * 80
 * + 1 per COMPLETED reservation in the last 30 days (at most +15)
 * − 5 per reservation the pharmacy cancelled as "Out of stock" in the last 30 days
 * − 10 per report about the pharmacy an admin upheld (RESOLVED) in the last 30 days
 * − 5 when the last nightly check found its stock not updated for 24+ hours (pharmacies.stale_penalty)
 * kept between 0 and 100
 * </pre>
 * The 30-day window lets a pharmacy recover. Recalculated after every reservation status change
 * and by the nightly TrustScoreJob (which also sets the stale penalty).
 */
public class TrustScoreService {

    public static final int BASE = 80;
    public static final int MAX_COMPLETED_BONUS = 15;
    public static final int OUT_OF_STOCK_PENALTY = 5;
    public static final int UPHELD_REPORT_PENALTY = 10;
    public static final int STALE_PENALTY = 5;
    public static final int WINDOW_DAYS = 30;
    public static final String NIGHTLY_ACTION = "TRUST_NIGHTLY_CHECK";

    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final ReservationDao reservationDao = new ReservationDao();
    private final ReportDao reportDao = new ReportDao();
    private final InventoryDao inventoryDao = new InventoryDao();
    private final AuditService auditService = new AuditService();
    private final NotificationService notificationService = new NotificationService();

    /** The parts of a score (for audit logs and explanations). */
    public static final class Breakdown {
        public final int completed;
        public final int outOfStockCancels;
        public final int upheldReports;
        public final int stalePenalty;
        public final int score;

        Breakdown(int completed, int outOfStockCancels, int upheldReports, int stalePenalty) {
            this.completed = completed;
            this.outOfStockCancels = outOfStockCancels;
            this.upheldReports = upheldReports;
            this.stalePenalty = stalePenalty;
            this.score = clamp(BASE + Math.min(MAX_COMPLETED_BONUS, completed)
                    - OUT_OF_STOCK_PENALTY * outOfStockCancels - UPHELD_REPORT_PENALTY * upheldReports - stalePenalty);
        }

        @Override
        public String toString() {
            return BASE + " + " + Math.min(MAX_COMPLETED_BONUS, completed) + " completed − "
                    + (OUT_OF_STOCK_PENALTY * outOfStockCancels) + " out-of-stock cancels − "
                    + (UPHELD_REPORT_PENALTY * upheldReports) + " upheld reports − " + stalePenalty + " stale = " + score;
        }
    }

    public static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    /** The score as it was / is at `asOf` (30-day window ending then), with the given stale penalty. */
    public Breakdown calculate(int pharmacyId, Date asOf, int stalePenalty) throws SQLException {
        String to = DateUtil.formatDateTime(new Date(asOf.getTime() + 1000));
        String from = DateUtil.formatDateTime(addDays(asOf, -WINDOW_DAYS));
        int completed = reservationDao.countChanged(pharmacyId, ReservationStatus.COMPLETED, null, null, from, to);
        int outOfStock = reservationDao.countChanged(pharmacyId, ReservationStatus.CANCELLED, "PHARMACY",
                CancelReason.OUT_OF_STOCK.name(), from, to);
        int upheld = reportDao.countUpheld(pharmacyId, from, to);
        return new Breakdown(completed, outOfStock, upheld, stalePenalty);
    }

    /** Recalculates and saves the score now; logs TRUST_SCORE_CHANGED when it moves. Returns the score. */
    public int recalculate(int pharmacyId) throws SQLException {
        Pharmacy p = pharmacyDao.findById(pharmacyId);
        if (p == null) {
            return 0;
        }
        Breakdown b = calculate(pharmacyId, new Date(), p.getStalePenalty());
        if (b.score != p.getTrustScore()) {
            pharmacyDao.updateTrustScore(pharmacyId, b.score);
            auditService.logAs(null, "TRUST_SCORE_CHANGED", "pharmacies", pharmacyId,
                    p.getTrustScore() + " → " + b.score + " (" + b + ")");
        }
        return b.score;
    }

    /** Recalculate, never failing the action that triggered it (errors are printed). */
    public void recalculateQuietly(int pharmacyId) {
        try {
            recalculate(pharmacyId);
        } catch (SQLException e) {
            ErrorLog.log("Trust score update failed for pharmacy " + pharmacyId, e);
        }
    }

    /** The score 7 days ago (same formula, window ending then; the current stale penalty). */
    public int scoreLastWeek(int pharmacyId) throws SQLException {
        Pharmacy p = pharmacyDao.findById(pharmacyId);
        return p == null ? 0 : calculate(pharmacyId, addDays(new Date(), -7), p.getStalePenalty()).score;
    }

    // ---------------------------------------------------------------- nightly

    /**
     * Nightly: for every VERIFIED pharmacy, stale_penalty = 5 if its newest stock update is
     * 24+ hours old (0 otherwise; no stock rows = not stale), notify the owner when the penalty
     * starts, then recalculate every score. Returns how many pharmacies are stale.
     */
    public int runNightlyCheck() throws SQLException {
        int stale = 0;
        for (Pharmacy p : pharmacyDao.findByStatus(PharmacyStatus.VERIFIED)) {
            String last = inventoryDao.lastStockUpdate(p.getId());
            int penalty = last != null && DateUtil.isOlderThanHours(last, SearchEngine.STALE_HOURS) ? STALE_PENALTY : 0;
            if (penalty > 0) {
                stale++;
            }
            if (penalty != p.getStalePenalty()) {
                pharmacyDao.updateStalePenalty(p.getId(), penalty);
                if (penalty > 0) {
                    notificationService.notifyUser(p.getOwnerId(), NotificationType.STALE,
                            "Your stock has not been updated for over 24 hours, so your trust score went down by "
                            + STALE_PENALTY + ". Update your stock or press \"Confirm all stock is correct\".",
                            NotificationService.LINK_PHARMACY, p.getId());
                }
            }
        }
        for (Pharmacy p : pharmacyDao.findAll()) {
            recalculate(p.getId());
        }
        auditService.logAs(null, NIGHTLY_ACTION, "pharmacies", null, stale + " pharmacy(ies) with stale stock");
        return stale;
    }

    private static Date addDays(Date d, int days) {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }
}
