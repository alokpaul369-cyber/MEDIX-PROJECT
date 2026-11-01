package medix.service;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import medix.dao.AreaDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.model.Area;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.Role;
import medix.model.User;
import medix.util.DBConnection;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Trust score = 80 + min(15, completed in 30 days) − 5 × out-of-stock cancels − 10 × upheld reports
 * − stale penalty, clamped to 0..100. Rows are written directly so each rule is tested alone.
 */
public class TrustScoreServiceTest {

    private static final int SECLO = 5;

    private static Pharmacy pharmacy;
    private static User patient;
    private static int seq;

    private final TrustScoreService trust = new TrustScoreService();

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        Area dhanmondi = new AreaDao().findByName("Dhanmondi");
        patient = user("ts-patient@test.com", Role.PATIENT, dhanmondi);
        User owner = user("ts-owner@test.com", Role.PHARMACY, dhanmondi);
        pharmacy = new Pharmacy();
        pharmacy.setOwnerId(owner.getId());
        pharmacy.setName("Trust Pharma");
        pharmacy.setLicenseNo("DL-TS-001");
        pharmacy.setAddress("Road 3, Dhanmondi");
        pharmacy.setAreaId(dhanmondi.getId());
        pharmacy.setOpenTime("08:00");
        pharmacy.setCloseTime("23:00");
        pharmacy.setStatus(PharmacyStatus.VERIFIED);
        new PharmacyDao().insert(pharmacy);
    }

    @Before
    public void clean() throws Exception {
        sql("DELETE FROM reports");
        sql("DELETE FROM reservations");
        sql("DELETE FROM inventory");
        sql("UPDATE pharmacies SET stale_penalty = 0 WHERE id = " + pharmacy.getId());
    }

    @Test
    public void aNewPharmacyStartsAt80() throws Exception {
        assertEquals(TrustScoreService.BASE, trust.recalculate(pharmacy.getId()));
    }

    @Test
    public void completedReservationsAddUpTo15() throws Exception {
        for (int i = 0; i < 4; i++) {
            reservation("COMPLETED", null, null, -2);
        }
        assertEquals(84, score());
        for (int i = 0; i < 20; i++) {
            reservation("COMPLETED", null, null, -3);
        }
        assertEquals("the bonus is capped", 95, score());
    }

    @Test
    public void onlyTheLast30DaysCount() throws Exception {
        reservation("COMPLETED", null, null, -10);
        reservation("COMPLETED", null, null, -40); // too old
        reservation("CANCELLED", "PHARMACY", "OUT_OF_STOCK", -45); // too old
        assertEquals(81, score());
    }

    @Test
    public void outOfStockCancelsUpheldReportsAndStaleStockLowerIt() throws Exception {
        reservation("CANCELLED", "PHARMACY", "OUT_OF_STOCK", -1);
        reservation("CANCELLED", "PHARMACY", "PATIENT_ASKED", -1); // not the pharmacy's fault
        reservation("CANCELLED", "PATIENT", null, -1);
        assertEquals(75, score());
        report("RESOLVED", -2);
        report("DISMISSED", -2); // dismissed reports do not count
        report("OPEN", -2);
        assertEquals(65, score());
        sql("UPDATE pharmacies SET stale_penalty = 5 WHERE id = " + pharmacy.getId());
        assertEquals(60, score());
    }

    @Test
    public void theScoreStaysBetween0And100() throws Exception {
        for (int i = 0; i < 9; i++) {
            report("RESOLVED", -1);
        }
        assertEquals(0, score());
        assertEquals(0, TrustScoreService.clamp(-40));
        assertEquals(100, TrustScoreService.clamp(130));
    }

    @Test
    public void lastWeekUsesTheWindowEndingSevenDaysAgo() throws Exception {
        reservation("COMPLETED", null, null, -2); // after last week's window ended
        reservation("COMPLETED", null, null, -10);
        assertEquals(82, score());
        assertEquals(81, trust.scoreLastWeek(pharmacy.getId()));
        assertEquals(81, trust.calculate(pharmacy.getId(), new Date(System.currentTimeMillis() - 7L * 86400000L), 0).score);
    }

    // ---------------------------------------------------------------- helpers

    private int score() throws Exception {
        return trust.recalculate(pharmacy.getId());
    }

    private static void reservation(String status, String by, String reason, int daysAgo) throws Exception {
        seq++;
        String when = "datetime('now', 'localtime', '" + daysAgo + " days')";
        sql("INSERT INTO reservations (code, patient_id, pharmacy_id, medicine_id, quantity, status, created_at, "
                + "expires_at, updated_at, cancelled_by, cancel_reason) VALUES ('TS-" + seq + "', " + patient.getId()
                + ", " + pharmacy.getId() + ", " + SECLO + ", 1, '" + status + "', " + when + ", " + when + ", " + when
                + ", " + (by == null ? "NULL" : "'" + by + "'") + ", " + (reason == null ? "NULL" : "'" + reason + "'") + ")");
    }

    private static void report(String status, int daysAgo) throws Exception {
        String when = "datetime('now', 'localtime', '" + daysAgo + " days')";
        sql("INSERT INTO reports (reporter_id, pharmacy_id, reason, details, status, created_at, resolved_at) VALUES ("
                + patient.getId() + ", " + pharmacy.getId() + ", 'WRONG_STOCK', 'test report text', '" + status + "', "
                + when + ", " + ("OPEN".equals(status) ? "NULL" : when) + ")");
    }

    private static User user(String email, Role role, Area area) throws Exception {
        User u = new User();
        u.setFullName(email);
        u.setEmail(email);
        u.setPhone("01711111111");
        u.setSalt("c2FsdA==");
        u.setPasswordHash("x");
        u.setRole(role);
        u.setAreaId(area.getId());
        u.setActive(true);
        new UserDao().insert(u);
        return u;
    }

    private static void sql(String s) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(s);
        }
    }
}
