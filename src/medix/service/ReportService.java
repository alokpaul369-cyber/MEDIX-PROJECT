package medix.service;

import java.sql.SQLException;
import java.util.List;
import medix.dao.ReportDao;
import medix.dao.ReservationDao;
import medix.model.NotificationType;
import medix.model.Report;
import medix.model.ReportReason;
import medix.model.ReportStatus;
import medix.model.ReportView;
import medix.model.ReservationView;
import medix.model.Role;
import medix.util.DateUtil;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * Patients report a problem with a pharmacy (from one of their reservations).
 * Admins uphold (RESOLVED, trust score −10 for 30 days) or dismiss them.
 */
public class ReportService {

    public static final int MIN_DETAILS = 10;
    public static final int MAX_DETAILS = 1000;
    public static final int MIN_ADMIN_NOTE = 5;
    private static final String MSG_HANDLED = "This report was already handled. The list was refreshed.";

    private final ReportDao reportDao = new ReportDao();
    private final TrustScoreService trustScoreService = new TrustScoreService();
    private final ReservationDao reservationDao = new ReservationDao();
    private final NotificationService notificationService = new NotificationService();
    private final AuditService auditService = new AuditService();

    /** Saves an OPEN report and notifies every admin. Returns the report id. */
    public int reportReservation(int reporterId, int reservationId, ReportReason reason, String details)
            throws ServiceException, SQLException {
        Integer me = Session.getUserId();
        if (me != null && me != reporterId) {
            throw new ServiceException("You can only report your own reservations.");
        }
        ReservationView v = reservationDao.findView(reservationId);
        if (v == null || v.getReservation().getPatientId() != reporterId) {
            throw new ServiceException("This reservation was not found.");
        }
        if (reason == null) {
            throw new ServiceException("Choose what went wrong.");
        }
        String text = ValidationUtil.trimToNull(details);
        if (text == null || text.length() < MIN_DETAILS) {
            throw new ServiceException("Describe the problem in a few words (at least " + MIN_DETAILS + " characters).");
        }
        if (text.length() > MAX_DETAILS) {
            throw new ServiceException("Please keep the description under " + MAX_DETAILS + " characters.");
        }
        if (reportDao.existsOpen(reporterId, reservationId)) {
            throw new ServiceException("You already reported this reservation. The admin will look at it.");
        }
        Report r = new Report();
        r.setReporterId(reporterId);
        r.setPharmacyId(v.getReservation().getPharmacyId());
        r.setReservationId(reservationId);
        r.setReason(reason);
        r.setDetails(text);
        r.setStatus(ReportStatus.OPEN);
        int id = reportDao.insert(r);
        notificationService.notifyAdmins(NotificationType.REPORT, "New report about " + v.getPharmacyName() + ": "
                + reason + " (reservation " + v.getReservation().getCode() + ").", NotificationService.LINK_REPORT, id);
        auditService.logAs(reporterId, "REPORT_CREATED", "reports", id,
                reason.name() + " about pharmacy #" + v.getReservation().getPharmacyId());
        return id;
    }

    // ---------------------------------------------------------------- admin (Part 9)

    /** Reports with the status (null = all), open ones first. */
    public List<ReportView> getReports(ReportStatus status) throws ServiceException, SQLException {
        requireAdmin();
        return reportDao.findViews(status);
    }

    public ReportView getReport(int reportId) throws ServiceException, SQLException {
        requireAdmin();
        return reportDao.findView(reportId);
    }

    /** Open reports (sidebar "Reports (n)"). */
    public int countOpen() throws SQLException {
        return reportDao.countByStatus(ReportStatus.OPEN);
    }

    /** Reports about the pharmacy that were upheld before (any time). */
    public int countUpheld(int pharmacyId) throws SQLException {
        return reportDao.countUpheld(pharmacyId);
    }

    /**
     * Upholds an OPEN report: status RESOLVED (resolved_at = now) with the admin note, the
     * pharmacy's trust score is recalculated (−10 for 30 days), reporter and pharmacy owner are
     * notified. Returns the new trust score.
     */
    public int uphold(int reportId, String adminNote) throws ServiceException, SQLException {
        requireAdmin();
        String note = requireNote(adminNote);
        ReportView v = loadOpen(reportId);
        if (!reportDao.close(reportId, ReportStatus.RESOLVED, note, DateUtil.now())) {
            throw new ServiceException(MSG_HANDLED);
        }
        int before = v.getPharmacyTrustScore();
        int after = trustScoreService.recalculate(v.getReport().getPharmacyId());
        notificationService.notifyUser(v.getReport().getReporterId(), NotificationType.REPORT,
                "Your report about " + v.getPharmacyName() + " was upheld. Admin note: " + note,
                NotificationService.LINK_REPORT, reportId);
        notificationService.notifyUser(v.getPharmacyOwnerId(), NotificationType.REPORT,
                "A patient report about your pharmacy was upheld (" + v.getReport().getReason() + "). "
                + "Trust score " + before + " → " + after + ". Admin note: " + note,
                NotificationService.LINK_REPORT, reportId);
        auditService.log("REPORT_UPHELD", "reports", reportId, v.getPharmacyName() + " (pharmacy #"
                + v.getReport().getPharmacyId() + "), trust " + before + " → " + after + ": " + note);
        return after;
    }

    /** Dismisses an OPEN report (status DISMISSED) and tells the reporter why. */
    public void dismiss(int reportId, String adminNote) throws ServiceException, SQLException {
        requireAdmin();
        String note = requireNote(adminNote);
        ReportView v = loadOpen(reportId);
        if (!reportDao.close(reportId, ReportStatus.DISMISSED, note, DateUtil.now())) {
            throw new ServiceException(MSG_HANDLED);
        }
        notificationService.notifyUser(v.getReport().getReporterId(), NotificationType.REPORT,
                "Your report about " + v.getPharmacyName() + " was reviewed and dismissed. Admin note: " + note,
                NotificationService.LINK_REPORT, reportId);
        auditService.log("REPORT_DISMISSED", "reports", reportId, v.getPharmacyName() + " (pharmacy #"
                + v.getReport().getPharmacyId() + "): " + note);
    }

    private ReportView loadOpen(int reportId) throws ServiceException, SQLException {
        ReportView v = reportDao.findView(reportId);
        if (v == null) {
            throw new ServiceException("This report no longer exists.");
        }
        if (v.getReport().getStatus() != ReportStatus.OPEN) {
            throw new ServiceException(MSG_HANDLED);
        }
        return v;
    }

    private static String requireNote(String adminNote) throws ServiceException {
        String note = ValidationUtil.trimToNull(adminNote);
        if (note == null || note.length() < MIN_ADMIN_NOTE) {
            throw new ServiceException("Write a short admin note (at least " + MIN_ADMIN_NOTE
                    + " characters). The patient will see it.");
        }
        if (note.length() > MAX_DETAILS) {
            throw new ServiceException("Please keep the note under " + MAX_DETAILS + " characters.");
        }
        return note;
    }

    private static void requireAdmin() throws ServiceException {
        if (!Session.hasRole(Role.ADMIN)) {
            throw new ServiceException("Only an admin can do this.");
        }
    }
}
