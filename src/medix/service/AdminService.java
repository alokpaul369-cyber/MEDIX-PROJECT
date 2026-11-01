package medix.service;

import java.io.File;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import medix.dao.PharmacyDao;
import medix.dao.ReportDao;
import medix.dao.ReservationDao;
import medix.dao.SearchLogDao;
import medix.model.DashboardStats;
import medix.model.NamedCount;
import medix.model.PharmacyStatus;
import medix.model.ReportView;
import medix.model.ReservationStatus;
import medix.model.Role;
import medix.model.ShortageRow;
import medix.util.DateUtil;
import medix.util.Session;

/**
 * Admin dashboard numbers. A period of N days is today and the N − 1 days before it
 * (from 00:00 of the first day until the end of today); "previous" is the N days before that.
 */
public class AdminService {

    public static final int TOP_MEDICINES = 8;
    public static final int SHORTAGE_ROWS = 10;
    /** Shortage rows with this many failed searches or more are shown light red. */
    public static final int SHORTAGE_ALERT = 20;
    public static final int OPEN_REPORT_ROWS = 5;

    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final SearchLogDao searchLogDao = new SearchLogDao();
    private final ReservationDao reservationDao = new ReservationDao();
    private final ReportDao reportDao = new ReportDao();
    private final AuditService auditService = new AuditService();

    public DashboardStats getStats(int days) throws ServiceException, SQLException {
        requireAdmin();
        String from = periodStart(days, 0);
        String to = periodEnd();
        DashboardStats s = new DashboardStats();
        s.setDays(days);
        s.setVerifiedPharmacies(pharmacyDao.countByStatus(PharmacyStatus.VERIFIED));
        s.setAreasCovered(pharmacyDao.countAreasWithStatus(PharmacyStatus.VERIFIED));
        s.setPendingPharmacies(pharmacyDao.countByStatus(PharmacyStatus.PENDING));
        s.setSearches(searchLogDao.countBetween(from, to));
        s.setPreviousSearches(searchLogDao.countBetween(periodStart(days, 1), from));
        s.setCompletedReservations(reservationDao.countCreatedBetween(from, to, ReservationStatus.COMPLETED));
        s.setClosedReservations(reservationDao.countCreatedBetween(from, to, ReservationStatus.COMPLETED,
                ReservationStatus.CANCELLED, ReservationStatus.EXPIRED));
        return s;
    }

    /** The most searched medicines of the period (top 8). */
    public List<NamedCount> getTopSearched(int days) throws ServiceException, SQLException {
        requireAdmin();
        return searchLogDao.topMedicines(periodStart(days, 0), periodEnd(), TOP_MEDICINES);
    }

    /** Searches per day for every day of the period (yyyy-MM-dd → count, 0 for quiet days), oldest first. */
    public Map<String, Integer> getSearchesPerDay(int days) throws ServiceException, SQLException {
        requireAdmin();
        Map<String, Integer> found = searchLogDao.countPerDay(periodStart(days, 0), periodEnd());
        Map<String, Integer> perDay = new LinkedHashMap<String, Integer>();
        for (int i = days - 1; i >= 0; i--) {
            String day = DateUtil.todayPlusDays(-i);
            Integer n = found.get(day);
            perDay.put(day, n == null ? 0 : n);
        }
        return perDay;
    }

    /** Medicine + area pairs searched and found nowhere in the period (top 10). */
    public List<ShortageRow> getShortages(int days) throws ServiceException, SQLException {
        requireAdmin();
        return searchLogDao.findShortages(periodStart(days, 0), periodEnd(), SHORTAGE_ROWS);
    }

    /** The newest open reports (top 5). */
    public List<ReportView> getLatestOpenReports() throws ServiceException, SQLException {
        requireAdmin();
        return reportDao.findLatestOpen(OPEN_REPORT_ROWS);
    }

    /** Audit for "Export PNG". */
    public void logChartExport(File folder, int files) {
        auditService.log("DASHBOARD_EXPORTED", null, null, files + " chart(s) to " + folder.getAbsolutePath());
    }

    /** 00:00 of the first day of the period; `periodsBack` = 1 gives the previous period. */
    static String periodStart(int days, int periodsBack) {
        return DateUtil.todayPlusDays(-(days - 1) - periodsBack * days) + " 00:00:00";
    }

    /** 00:00 tomorrow, i.e. the end of today. */
    static String periodEnd() {
        return DateUtil.todayPlusDays(1) + " 00:00:00";
    }

    private static void requireAdmin() throws ServiceException {
        if (!Session.hasRole(Role.ADMIN)) {
            throw new ServiceException("Only an admin can do this.");
        }
    }
}
