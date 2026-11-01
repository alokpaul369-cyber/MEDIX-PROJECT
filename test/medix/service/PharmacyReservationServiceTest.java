package medix.service;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import medix.dao.AreaDao;
import medix.dao.InventoryDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.engine.AvailabilityCache;
import medix.model.Area;
import medix.model.CancelReason;
import medix.model.Inventory;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.ReservationStatus;
import medix.model.ReservationView;
import medix.model.Role;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The pharmacy side of reservations and the trust score, on a temporary database.
 * Medicine 5 = Seclo 20 mg from the seed.
 */
public class PharmacyReservationServiceTest {

    private static final int SECLO = 5;

    private static Area dhanmondi;
    private static User owner;
    private static Pharmacy pharmacy;
    private static User otherOwner;
    private static Pharmacy otherPharmacy;
    private static int patientA;
    private static int patientB;
    private final PharmacyReservationService service = new PharmacyReservationService();
    private final TrustScoreService trust = new TrustScoreService();

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-pharmacy-reservation-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        dhanmondi = new AreaDao().findByName("Dhanmondi");
        owner = user("owner@test.com", Role.PHARMACY);
        pharmacy = pharmacy(owner, "Green Life Pharmacy", "DL-700700");
        otherOwner = user("owner2@test.com", Role.PHARMACY);
        otherPharmacy = pharmacy(otherOwner, "Other Pharmacy", "DL-800800");
        patientA = user("a@test.com", Role.PATIENT).getId();
        patientB = user("b@test.com", Role.PATIENT).getId();
    }

    @Before
    public void reset() throws Exception {
        Session.logout();
        sql("DELETE FROM reports");
        sql("DELETE FROM reservations");
        sql("DELETE FROM inventory");
        sql("DELETE FROM notifications");
        sql("UPDATE pharmacies SET trust_score = 80, stale_penalty = 0");
        AvailabilityCache.clear();
    }

    @After
    public void logout() {
        Session.logout();
    }

    @Test
    public void pendingConfirmedReadyPickedUpKeepsStockRight() throws Exception {
        int b = batch(pharmacy, SECLO, "S-1", 10, 100);
        ReservationView v = reserve(patientA, 4);
        int id = v.getReservation().getId();
        assertStock(b, 10, 4);

        asPharmacy();
        service.confirm(id);
        assertEquals(ReservationStatus.CONFIRMED, status(id));
        assertStock(b, 10, 4);
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + patientA
                + " AND message = 'Green Life Pharmacy confirmed " + v.getReservation().getCode() + ".'"));
        expectFailure(() -> service.confirm(id), "Only a pending reservation");

        service.markReady(id);
        assertEquals(ReservationStatus.READY, status(id));
        assertStock(b, 10, 4);
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + patientA
                + " AND message LIKE 'Your medicine is ready%'"));

        service.pickUp(id);
        assertEquals(ReservationStatus.COMPLETED, status(id));
        assertStock(b, 6, 0);
        assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE inventory_id = " + b
                + " AND reason = 'PICKED_UP' AND change_qty = -4"));
        assertEquals(81, trustScore(pharmacy));
        assertEquals(4, count("SELECT COUNT(*) FROM audit_logs WHERE entity = 'reservations' AND entity_id = " + id
                + " AND action IN ('RESERVATION_CONFIRMED','RESERVATION_READY','RESERVATION_PICKED_UP','RESERVATION_CREATED')"));
        expectFailure(() -> service.pickUp(id), "Only a confirmed or ready");
    }

    @Test
    public void pickUpNeedsConfirmationButNotReady() throws Exception {
        int b = batch(pharmacy, SECLO, "S-1", 10, 100);
        int id = reserve(patientA, 2).getReservation().getId();
        asPharmacy();
        expectFailure(() -> service.markReady(id), "Only a confirmed");
        expectFailure(() -> service.pickUp(id), "Only a confirmed or ready");
        service.confirm(id);
        service.pickUp(id);          // CONFIRMED → COMPLETED directly
        assertStock(b, 8, 0);
    }

    @Test
    public void extendOnlyOnceAndOnlyWhileRunning() throws Exception {
        batch(pharmacy, SECLO, "S-1", 10, 100);
        ReservationView v = reserve(patientA, 1);
        int id = v.getReservation().getId();
        asPharmacy();
        service.extend(id);
        long added = DateUtil.parseDateTime(service.getReservations(PharmacyReservationService.Filter.ALL).get(0)
                .getReservation().getExpiresAt()).getTime() - DateUtil.parseDateTime(v.getReservation().getExpiresAt()).getTime();
        assertEquals(15 * 60 * 1000L, added);
        expectFailure(() -> service.extend(id), "already extended once");

        Session.logout();
        int id2 = reserve(patientB, 1).getReservation().getId();
        sql("UPDATE reservations SET expires_at = datetime('now', 'localtime', '-1 minutes') WHERE id = " + id2);
        asPharmacy();
        expectFailure(() -> service.extend(id2), "already ended");
    }

    @Test
    public void cancelOutOfStockReleasesUnitsAndLowersTrust() throws Exception {
        int b = batch(pharmacy, SECLO, "S-1", 10, 100);
        int id = reserve(patientA, 3).getReservation().getId();
        asPharmacy();
        expectFailure(() -> service.cancel(id, CancelReason.OTHER, " "), "Write the reason");
        service.cancel(id, CancelReason.OUT_OF_STOCK, null);
        assertEquals(ReservationStatus.CANCELLED, status(id));
        assertStock(b, 10, 0);
        assertEquals(1, count("SELECT COUNT(*) FROM reservations WHERE id = " + id
                + " AND cancelled_by = 'PHARMACY' AND cancel_reason = 'OUT_OF_STOCK'"));
        assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE reason = 'RELEASED' AND change_qty = -3"));
        assertEquals(75, trustScore(pharmacy));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + patientA
                + " AND message LIKE 'Green Life Pharmacy cancelled your reservation %: Out of stock.%'"));
    }

    @Test
    public void patientCancelsAndOtherReasonsDoNotLowerTrust() throws Exception {
        batch(pharmacy, SECLO, "S-1", 10, 100);
        int id = reserve(patientA, 1).getReservation().getId();
        new ReservationService().cancel(id, patientA);
        assertEquals(1, count("SELECT COUNT(*) FROM reservations WHERE id = " + id + " AND cancelled_by = 'PATIENT'"));
        int id2 = reserve(patientB, 1).getReservation().getId();
        asPharmacy();
        service.cancel(id2, CancelReason.PATIENT_ASKED, null);
        assertEquals(80, trustScore(pharmacy));
    }

    @Test
    public void anotherPharmacyCannotTouchTheReservation() throws Exception {
        batch(pharmacy, SECLO, "S-1", 10, 100);
        ReservationView v = reserve(patientA, 1);
        Session.login(otherOwner, otherPharmacy);
        expectFailure(() -> service.confirm(v.getReservation().getId()), "not found at your pharmacy");
        expectFailure(() -> service.findByCode(v.getReservation().getCode()), "No reservation");
        asPharmacy();
        assertEquals(v.getReservation().getId(), service.findByCode(v.getReservation().getCode().substring(3)).getId());
    }

    @Test
    public void trustFormulaWindowCapReportsAndClamp() throws Exception {
        int b = batch(pharmacy, SECLO, "S-1", 100, 100);
        for (int i = 0; i < 20; i++) {
            fakeReservation(b, "COMPLETED", null, null, -i);         // 20 completed in the last 20 days
        }
        fakeReservation(b, "COMPLETED", null, null, -40);            // outside the 30-day window
        assertEquals("80 + 15 (cap)", 95, trust.recalculate(pharmacy.getId()));

        sql("INSERT INTO reports (reporter_id, pharmacy_id, reason, details, status, resolved_at) VALUES ("
                + patientA + ", " + pharmacy.getId() + ", 'WRONG_STOCK', 'x', 'RESOLVED', datetime('now','localtime'))");
        sql("INSERT INTO reports (reporter_id, pharmacy_id, reason, details, status, resolved_at) VALUES ("
                + patientA + ", " + pharmacy.getId() + ", 'WRONG_STOCK', 'x', 'DISMISSED', datetime('now','localtime'))");
        assertEquals("one upheld report −10", 85, trust.recalculate(pharmacy.getId()));

        for (int i = 0; i < 30; i++) {
            fakeReservation(b, "CANCELLED", "PHARMACY", "OUT_OF_STOCK", -1);
        }
        assertEquals("never below 0", 0, trust.recalculate(pharmacy.getId()));
        assertTrue(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'TRUST_SCORE_CHANGED'") >= 3);
    }

    @Test
    public void lastWeekComparison() throws Exception {
        int b = batch(pharmacy, SECLO, "S-1", 100, 100);
        fakeReservation(b, "COMPLETED", null, null, 0);
        fakeReservation(b, "COMPLETED", null, null, -10);
        trust.recalculate(pharmacy.getId());
        assertEquals(82, trustScore(pharmacy));
        assertEquals("a week ago only the 10-day-old one counted", 81, trust.scoreLastWeek(pharmacy.getId()));
    }

    @Test
    public void nightlyCheckPenalisesStaleStock() throws Exception {
        int b = batch(pharmacy, SECLO, "S-1", 10, 100);
        batch(otherPharmacy, SECLO, "O-1", 10, 100);                 // fresh
        sql("UPDATE inventory SET updated_at = datetime('now', 'localtime', '-2 days') WHERE id = " + b);
        assertEquals(1, trust.runNightlyCheck());
        assertEquals(75, trustScore(pharmacy));
        assertEquals(80, trustScore(otherPharmacy));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + owner.getId() + " AND type = 'STALE'"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'TRUST_NIGHTLY_CHECK'"));

        sql("UPDATE inventory SET updated_at = datetime('now', 'localtime') WHERE id = " + b); // "Confirm all stock"
        assertEquals(0, trust.runNightlyCheck());
        assertEquals(80, trustScore(pharmacy));
    }

    @Test
    public void statsForTheHeaderAndFooter() throws Exception {
        batch(pharmacy, SECLO, "S-1", 10, 100);
        int a = reserve(patientA, 1).getReservation().getId();
        reserve(patientB, 1);
        asPharmacy();
        service.confirm(a);
        PharmacyReservationService.Stats s = service.getStats();
        assertEquals(2, s.active);
        assertEquals(1, s.toConfirm);
        service.pickUp(a);
        s = service.getStats();
        assertEquals(1, s.completedToday);
        assertEquals(81, s.trustScore);
        assertEquals(80, s.trustLastWeek);
    }

    // ---------------------------------------------------------------- helpers

    private interface Action {
        void run() throws Exception;
    }

    private static void expectFailure(Action action, String messagePart) throws Exception {
        try {
            action.run();
            fail("expected: " + messagePart);
        } catch (ServiceException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }

    private static void asPharmacy() throws Exception {
        Session.login(owner, new PharmacyDao().findById(pharmacy.getId()));
    }

    private static ReservationView reserve(int patient, int qty) throws Exception {
        Session.logout();
        return new ReservationService().reserve(patient, pharmacy.getId(), SECLO, qty, 45, null);
    }

    private static ReservationStatus status(int id) throws Exception {
        return new ReservationService().getView(id).getReservation().getStatus();
    }

    private static int trustScore(Pharmacy p) throws Exception {
        return new PharmacyDao().findById(p.getId()).getTrustScore();
    }

    private static void assertStock(int inventoryId, int quantity, int reserved) throws Exception {
        assertEquals("quantity", quantity, count("SELECT quantity FROM inventory WHERE id = " + inventoryId));
        assertEquals("reserved_qty", reserved, count("SELECT reserved_qty FROM inventory WHERE id = " + inventoryId));
    }

    private static int seq;

    /** A finished reservation written directly (for the score window tests). */
    private static void fakeReservation(int inventoryId, String status, String by, String reason, int daysAgo)
            throws Exception {
        seq++;
        String when = "datetime('now', 'localtime', '" + daysAgo + " days')";
        sql("INSERT INTO reservations (code, patient_id, pharmacy_id, medicine_id, inventory_id, quantity, status, "
                + "created_at, expires_at, updated_at, cancelled_by, cancel_reason) VALUES ('FAKE-" + seq + "', " + patientA
                + ", " + pharmacy.getId() + ", " + SECLO + ", " + inventoryId + ", 1, '" + status + "', " + when + ", "
                + when + ", " + when + ", " + (by == null ? "NULL" : "'" + by + "'") + ", "
                + (reason == null ? "NULL" : "'" + reason + "'") + ")");
    }

    private static User user(String email, Role role) throws Exception {
        User u = new User();
        u.setFullName(email);
        u.setEmail(email);
        u.setPhone("01819123552");
        u.setSalt("c2FsdA==");
        u.setPasswordHash("x");
        u.setRole(role);
        u.setAreaId(dhanmondi.getId());
        u.setActive(true);
        new UserDao().insert(u);
        return u;
    }

    private static Pharmacy pharmacy(User o, String name, String license) throws Exception {
        Pharmacy p = new Pharmacy();
        p.setOwnerId(o.getId());
        p.setName(name);
        p.setLicenseNo(license);
        p.setAddress("Road 1, Dhanmondi");
        p.setAreaId(dhanmondi.getId());
        p.setLatitude(dhanmondi.getLatitude());
        p.setLongitude(dhanmondi.getLongitude());
        p.setOpenTime("00:00");
        p.setCloseTime("00:00");
        p.setStatus(PharmacyStatus.VERIFIED);
        new PharmacyDao().insert(p);
        return p;
    }

    private static int batch(Pharmacy p, int medicineId, String batchNo, int qty, int expiryDays) throws Exception {
        Inventory i = new Inventory();
        i.setPharmacyId(p.getId());
        i.setMedicineId(medicineId);
        i.setBatchNo(batchNo);
        i.setQuantity(qty);
        i.setPrice(7.0);
        i.setExpiryDate(DateUtil.todayPlusDays(expiryDays));
        i.setLowStockThreshold(1);
        return new InventoryDao().insert(i);
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
