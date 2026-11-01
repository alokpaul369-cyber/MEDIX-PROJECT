package medix.service;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import medix.dao.AreaDao;
import medix.dao.PharmacyDao;
import medix.dao.ReportDao;
import medix.dao.UserDao;
import medix.model.Area;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.Report;
import medix.model.ReportReason;
import medix.model.ReportStatus;
import medix.model.ReportView;
import medix.model.Role;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.Session;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Admin handling of reports: upholding lowers the trust score, dismissing does not, and both are
 * notified and audited. Real SQLite database in a temporary folder (never data/medix.db).
 */
public class ReportServiceTest {

    private static final int SECLO = 5;

    private static Pharmacy pharmacy;
    private static User patient;
    private static int seq;

    private final ReportService reportService = new ReportService();
    private final TrustScoreService trustScoreService = new TrustScoreService();

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        Area dhanmondi = new AreaDao().findByName("Dhanmondi");
        patient = user("rp-patient@test.com", Role.PATIENT, dhanmondi);
        User owner = user("rp-owner@test.com", Role.PHARMACY, dhanmondi);
        pharmacy = new Pharmacy();
        pharmacy.setOwnerId(owner.getId());
        pharmacy.setName("Report Pharma");
        pharmacy.setLicenseNo("DL-RP-001");
        pharmacy.setAddress("Road 2, Dhanmondi");
        pharmacy.setAreaId(dhanmondi.getId());
        pharmacy.setOpenTime("08:00");
        pharmacy.setCloseTime("23:00");
        pharmacy.setStatus(PharmacyStatus.VERIFIED);
        new PharmacyDao().insert(pharmacy);
    }

    @Before
    public void cleanStart() throws Exception {
        sql("DELETE FROM reports");
        trustScoreService.recalculate(pharmacy.getId()); // back to the base score after the last test
        sql("DELETE FROM notifications");
        sql("DELETE FROM audit_logs");
        Session.login(new UserDao().findById(1), null); // seeded admin
    }

    @Test
    public void upholdingLowersTheTrustScoreAndNotifiesBothSides() throws Exception {
        int before = trustScore();
        assertEquals("nothing happened yet: base score", TrustScoreService.BASE, before);
        int id = openReport();

        int after = reportService.uphold(id, "Pharmacy confirmed the stock was wrong.");

        assertEquals(before - TrustScoreService.UPHELD_REPORT_PENALTY, after);
        assertEquals(after, trustScore());
        Report r = new ReportDao().findById(id);
        assertEquals(ReportStatus.RESOLVED, r.getStatus());
        assertEquals("Pharmacy confirmed the stock was wrong.", r.getAdminNote());
        assertNotNull(r.getResolvedAt());
        assertEquals(1, reportService.countUpheld(pharmacy.getId()));
        assertEquals("reporter notified", 1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + patient.getId()
                + " AND type = 'REPORT' AND link_entity = 'reports' AND link_id = " + id));
        assertEquals("owner notified", 1, count("SELECT COUNT(*) FROM notifications WHERE user_id = "
                + pharmacy.getOwnerId() + " AND type = 'REPORT' AND message LIKE '%Trust score 80 → 70%'"));
        assertEquals("admin action audited", 1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'REPORT_UPHELD' "
                + "AND user_id = 1 AND entity = 'reports' AND entity_id = " + id));
        assertEquals("trust change audited", 1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'TRUST_SCORE_CHANGED' "
                + "AND entity_id = " + pharmacy.getId()));
    }

    @Test
    public void dismissingKeepsTheTrustScore() throws Exception {
        int before = trustScore();
        int id = openReport();
        reportService.dismiss(id, "The patient came after closing time.");
        assertEquals(before, trustScore());
        assertEquals(ReportStatus.DISMISSED, new ReportDao().findById(id).getStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + patient.getId()
                + " AND message LIKE '%dismissed%'"));
        assertEquals("the pharmacy is not told", 0, count("SELECT COUNT(*) FROM notifications WHERE user_id = "
                + pharmacy.getOwnerId()));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'REPORT_DISMISSED' AND entity_id = " + id));
    }

    @Test
    public void aReportIsHandledOnlyOnce() throws Exception {
        int id = openReport();
        reportService.uphold(id, "Confirmed by phone.");
        int score = trustScore();
        try {
            reportService.uphold(id, "Confirmed by phone again.");
            fail("an upheld report cannot be upheld again");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("already handled"));
        }
        try {
            reportService.dismiss(id, "Changed my mind.");
            fail("an upheld report cannot be dismissed");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("already handled"));
        }
        assertEquals("the penalty is applied once", score, trustScore());
    }

    @Test
    public void theAdminNoteIsRequiredAndOnlyAdminsDecide() throws Exception {
        int id = openReport();
        try {
            reportService.uphold(id, "ok");
            fail("a 2-letter note is too short");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("admin note"));
        }
        Session.login(patient, null);
        try {
            reportService.dismiss(id, "Not a real problem.");
            fail("a patient cannot handle reports");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("admin"));
        }
        assertEquals(ReportStatus.OPEN, new ReportDao().findById(id).getStatus());
    }

    @Test
    public void viewsJoinReporterPharmacyAndReservation() throws Exception {
        int id = openReport();
        ReportView v = reportService.getReport(id);
        assertEquals(patient.getFullName(), v.getReporterName());
        assertEquals("Report Pharma", v.getPharmacyName());
        assertEquals(PharmacyStatus.VERIFIED, v.getPharmacyStatus());
        assertTrue(v.getReservationCode().startsWith("RP-"));
        assertTrue(v.getReservationSummary().startsWith("Seclo 20 mg Capsule × 2 · Expired"));
        assertEquals(1, reportService.countOpen());
        assertEquals(1, reportService.getReports(ReportStatus.OPEN).size());
        assertEquals(0, reportService.getReports(ReportStatus.DISMISSED).size());
        assertEquals(1, reportService.getReports(null).size());
    }

    // ---------------------------------------------------------------- helpers

    /** An OPEN report about an expired reservation of the patient. */
    private static int openReport() throws Exception {
        seq++;
        sql("INSERT INTO reservations (code, patient_id, pharmacy_id, medicine_id, quantity, status, expires_at) "
                + "VALUES ('RP-" + seq + "', " + patient.getId() + ", " + pharmacy.getId() + ", " + SECLO
                + ", 2, 'EXPIRED', datetime('now', 'localtime'))");
        int reservationId = count("SELECT MAX(id) FROM reservations");
        Report r = new Report();
        r.setReporterId(patient.getId());
        r.setPharmacyId(pharmacy.getId());
        r.setReservationId(reservationId);
        r.setReason(ReportReason.WRONG_STOCK);
        r.setDetails("They had no stock although the app showed 20.");
        return new ReportDao().insert(r);
    }

    private static int trustScore() throws Exception {
        return new PharmacyDao().findById(pharmacy.getId()).getTrustScore();
    }

    private static User user(String email, Role role, Area area) throws Exception {
        User u = new User();
        u.setFullName("User " + email);
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

    private static int count(String s) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(s)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
