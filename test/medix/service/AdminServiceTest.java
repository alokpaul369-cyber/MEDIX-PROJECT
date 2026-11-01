package medix.service;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import medix.dao.AreaDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.model.Area;
import medix.model.DashboardStats;
import medix.model.NamedCount;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.Role;
import medix.model.ShortageRow;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.Session;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Dashboard numbers against a real SQLite database in a temporary folder (never data/medix.db).
 * Seeded medicine ids: 1 = Napa 500 mg, 5 = Seclo 20 mg.
 */
public class AdminServiceTest {

    private static final int NAPA = 1;
    private static final int SECLO = 5;

    private static Area dhanmondi;
    private static Area mirpur;
    private static int pharmacyId;
    private static int patientId;
    private static int seq;

    private final AdminService adminService = new AdminService();

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        dhanmondi = new AreaDao().findByName("Dhanmondi");
        mirpur = new AreaDao().findByName("Mirpur 10");
        pharmacyId = pharmacy("Dash Dhanmondi", "DL-DASH-01", "dash1@test.com", dhanmondi, PharmacyStatus.VERIFIED);
        pharmacy("Dash Mirpur", "DL-DASH-02", "dash2@test.com", mirpur, PharmacyStatus.VERIFIED);
        pharmacy("Dash Mirpur 2", "DL-DASH-03", "dash3@test.com", mirpur, PharmacyStatus.VERIFIED);
        pharmacy("Dash Pending", "DL-DASH-04", "dash4@test.com", dhanmondi, PharmacyStatus.PENDING);
        patientId = user("dash-patient@test.com", Role.PATIENT).getId();
    }

    @Before
    public void asAdmin() throws Exception {
        sql("DELETE FROM search_logs");
        sql("DELETE FROM reservations");
        Session.login(new UserDao().findById(1), null);
    }

    @Test
    public void cardsCountThePeriodAndCompareWithThePeriodBefore() throws Exception {
        for (int i = 0; i < 3; i++) {
            searchLog(SECLO, dhanmondi, 4, 0);
        }
        searchLog(NAPA, dhanmondi, 2, -5);
        searchLog(NAPA, mirpur, 2, -5);
        for (int i = 0; i < 4; i++) {
            searchLog(NAPA, dhanmondi, 1, -10); // previous 7-day period
        }
        DashboardStats week = adminService.getStats(7);
        assertEquals(5, week.getSearches());
        assertEquals(4, week.getPreviousSearches());
        assertEquals(Integer.valueOf(25), week.getSearchChangePercent());

        DashboardStats month = adminService.getStats(30);
        assertEquals(9, month.getSearches());
        assertEquals(0, month.getPreviousSearches());
        assertNull("no searches before: no percentage", month.getSearchChangePercent());

        assertEquals(3, week.getVerifiedPharmacies());
        assertEquals("Dhanmondi and Mirpur 10", 2, week.getAreasCovered());
        assertTrue(week.getPendingPharmacies() >= 1);
    }

    @Test
    public void completedShareCountsOnlyFinishedReservationsOfThePeriod() throws Exception {
        reservation("COMPLETED", 0);
        reservation("COMPLETED", -1);
        reservation("COMPLETED", -2);
        reservation("EXPIRED", 0);
        reservation("PENDING", 0); // still running: not counted
        reservation("COMPLETED", -20); // outside the 7 days
        DashboardStats s = adminService.getStats(7);
        assertEquals(3, s.getCompletedReservations());
        assertEquals(4, s.getClosedReservations());
        assertEquals(75, s.getCompletedPercent());
        assertEquals(4, adminService.getStats(30).getCompletedReservations());
    }

    @Test
    public void searchesPerDayHasEveryDayOfThePeriod() throws Exception {
        searchLog(SECLO, dhanmondi, 1, 0);
        searchLog(SECLO, dhanmondi, 1, 0);
        searchLog(SECLO, dhanmondi, 1, -3);
        Map<String, Integer> perDay = adminService.getSearchesPerDay(7);
        assertEquals(7, perDay.size());
        assertEquals("oldest first", DateUtil.todayPlusDays(-6), perDay.keySet().iterator().next());
        assertEquals(Integer.valueOf(2), perDay.get(DateUtil.today()));
        assertEquals(Integer.valueOf(1), perDay.get(DateUtil.todayPlusDays(-3)));
        assertEquals(Integer.valueOf(0), perDay.get(DateUtil.todayPlusDays(-1)));
    }

    @Test
    public void topMedicinesAndShortages() throws Exception {
        for (int i = 0; i < 3; i++) {
            searchLog(SECLO, dhanmondi, 0, 0); // found nowhere
        }
        searchLog(SECLO, mirpur, 0, -1);
        searchLog(SECLO, mirpur, 2, -1); // found: not a shortage
        searchLog(NAPA, dhanmondi, 3, 0);
        searchLog(null, dhanmondi, 0, 0); // matched no medicine: not in either list

        List<NamedCount> top = adminService.getTopSearched(7);
        assertEquals(2, top.size());
        assertEquals("Seclo 20 mg", top.get(0).getLabel());
        assertEquals(5, top.get(0).getCount());
        assertEquals(1, top.get(1).getCount());

        List<ShortageRow> shortages = adminService.getShortages(7);
        assertEquals(2, shortages.size());
        assertEquals(SECLO, shortages.get(0).getMedicineId());
        assertEquals("Dhanmondi", shortages.get(0).getAreaName());
        assertEquals(3, shortages.get(0).getFailed());
        assertEquals("Mirpur 10", shortages.get(1).getAreaName());
        assertEquals(1, shortages.get(1).getFailed());
    }

    @Test
    public void onlyAnAdminSeesTheDashboard() throws Exception {
        Session.login(new UserDao().findById(patientId), null);
        try {
            adminService.getStats(7);
            fail("a patient must not read the dashboard");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("admin"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void searchLog(Integer medicineId, Area area, int results, int daysAgo) throws Exception {
        sql("INSERT INTO search_logs (user_id, query, medicine_id, area_id, results_count, created_at) VALUES ("
                + patientId + ", 'q', " + medicineId + ", " + area.getId() + ", " + results
                + ", datetime('now', 'localtime', '" + daysAgo + " days'))");
    }

    private static void reservation(String status, int daysAgo) throws Exception {
        seq++;
        String when = "datetime('now', 'localtime', '" + daysAgo + " days')";
        sql("INSERT INTO reservations (code, patient_id, pharmacy_id, medicine_id, quantity, status, created_at, "
                + "expires_at, updated_at) VALUES ('DASH-" + seq + "', " + patientId + ", " + pharmacyId + ", " + SECLO
                + ", 1, '" + status + "', " + when + ", " + when + ", " + when + ")");
    }

    private static User user(String email, Role role) throws Exception {
        User u = new User();
        u.setFullName("User " + email);
        u.setEmail(email);
        u.setPhone("01711111111");
        u.setSalt("c2FsdA==");
        u.setPasswordHash("x");
        u.setRole(role);
        u.setAreaId(dhanmondi.getId());
        u.setActive(true);
        new UserDao().insert(u);
        return u;
    }

    private static int pharmacy(String name, String license, String email, Area area, PharmacyStatus status)
            throws Exception {
        User owner = user(email, Role.PHARMACY);
        Pharmacy p = new Pharmacy();
        p.setOwnerId(owner.getId());
        p.setName(name);
        p.setLicenseNo(license);
        p.setAddress("Road 1, " + area.getName());
        p.setAreaId(area.getId());
        p.setLatitude(area.getLatitude());
        p.setLongitude(area.getLongitude());
        p.setOpenTime("08:00");
        p.setCloseTime("23:00");
        p.setStatus(status);
        return new PharmacyDao().insert(p);
    }

    private static void sql(String s) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(s);
        }
    }
}
