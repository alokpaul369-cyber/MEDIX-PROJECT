package medix.service;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import medix.dao.AreaDao;
import medix.dao.InventoryDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.engine.AvailabilityCache;
import medix.engine.SearchEngine;
import medix.model.Area;
import medix.model.Inventory;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.ReportReason;
import medix.model.ReservationStatus;
import medix.model.ReservationView;
import medix.model.Role;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.DateUtil;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Reservations against a real SQLite database in a temporary folder (never data/medix.db).
 * Medicine ids from the seed: 1 = Napa 500 mg, 5 = Seclo 20 mg.
 */
public class ReservationServiceTest {

    private static final int NAPA = 1;
    private static final int SECLO = 5;
    private static final int MINUTES = 45;

    private static Area dhanmondi;
    private static int pharmacyId;
    private static int ownerId;
    private static final List<Integer> patients = new ArrayList<Integer>();
    private final ReservationService service = new ReservationService();

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-reservation-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        dhanmondi = new AreaDao().findByName("Dhanmondi");
        ownerId = user("owner@test.com", Role.PHARMACY);
        Pharmacy p = new Pharmacy();
        p.setOwnerId(ownerId);
        p.setName("Test Pharmacy");
        p.setLicenseNo("DL-900900");
        p.setAddress("Road 1, Dhanmondi");
        p.setAreaId(dhanmondi.getId());
        p.setLatitude(dhanmondi.getLatitude());
        p.setLongitude(dhanmondi.getLongitude());
        p.setOpenTime("00:00");
        p.setCloseTime("00:00"); // open 24 hours
        p.setStatus(PharmacyStatus.VERIFIED);
        pharmacyId = new PharmacyDao().insert(p);
        for (int i = 0; i < 12; i++) {
            patients.add(user("patient" + i + "@test.com", Role.PATIENT));
        }
    }

    @Before
    public void reset() throws Exception {
        sql("DELETE FROM reports");
        sql("DELETE FROM reservations");
        sql("DELETE FROM inventory");
        sql("UPDATE pharmacies SET open_time = '00:00', close_time = '00:00', status = 'VERIFIED'");
        AvailabilityCache.clear();
    }

    @Test
    public void reserveUsesTheFirstExpiringBatchAndLowersAvailableInSearch() throws Exception {
        int soon = batch(NAPA, "SOON", 3, 10);
        int later = batch(NAPA, "LATER", 20, 100);
        assertEquals(23, availableInSearch(NAPA));

        ReservationView v = service.reserve(patient(0), pharmacyId, NAPA, 2, MINUTES, "after office");
        assertEquals(soon, (int) v.getReservation().getInventoryId());
        assertEquals(ReservationStatus.PENDING, v.getReservation().getStatus());
        assertTrue(v.getReservation().getCode(), v.getReservation().getCode().matches("MX-\\d{4}-\\d{4}"));
        assertEquals(String.format("-%04d", v.getReservation().getId()),
                v.getReservation().getCode().substring(v.getReservation().getCode().length() - 5));
        long minutes = ReservationService.minutesLeft(v.getReservation());
        assertTrue("hold about 45 min, was " + minutes, minutes >= 43 && minutes <= 45);
        assertEquals(2, reserved(soon));
        assertEquals("another patient sees 2 fewer", 21, availableInSearch(NAPA));

        // 4 more: the SOON batch has only 1 left, so the LATER batch is used (no splitting)
        ReservationView v2 = service.reserve(patient(1), pharmacyId, NAPA, 4, MINUTES, null);
        assertEquals(later, (int) v2.getReservation().getInventoryId());
        assertEquals(17, availableInSearch(NAPA));

        assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE reason = 'RESERVED' AND change_qty = 2 "
                + "AND note LIKE '" + v.getReservation().getCode() + "%'"));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + ownerId
                + " AND message LIKE 'New reservation " + v.getReservation().getCode() + ": Napa 500 mg %'"));
    }

    @Test
    public void unitsAreNeverSplitAcrossBatches() throws Exception {
        batch(NAPA, "A", 3, 10);
        batch(NAPA, "B", 3, 20);
        try {
            service.reserve(patient(0), pharmacyId, NAPA, 5, MINUTES, null);
            fail("5 units are not in one batch");
        } catch (ServiceException e) {
            assertEquals("Only 3 available now.", e.getMessage());
        }
    }

    @Test
    public void expiredBatchesAreNeverReserved() throws Exception {
        batch(NAPA, "OLD", 50, -1);
        try {
            service.reserve(patient(0), pharmacyId, NAPA, 1, MINUTES, null);
            fail("only an expired batch exists");
        } catch (ServiceException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("None available now"));
        }
    }

    @Test
    public void limitsThreeActiveAndOnePerMedicineAndPharmacy() throws Exception {
        batch(NAPA, "N", 50, 100);
        batch(SECLO, "S", 50, 100);
        batch(2, "NX", 50, 100);
        batch(4, "ACE", 50, 100);
        service.reserve(patient(0), pharmacyId, NAPA, 1, 30, null);
        try {
            service.reserve(patient(0), pharmacyId, NAPA, 1, 30, null);
            fail("second active reservation of the same medicine here");
        } catch (ServiceException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("You already reserved Napa 500 mg"));
        }
        service.reserve(patient(0), pharmacyId, SECLO, 1, 30, null);
        service.reserve(patient(0), pharmacyId, 2, 1, 30, null);
        try {
            service.reserve(patient(0), pharmacyId, 4, 1, 30, null);
            fail("fourth active reservation");
        } catch (ServiceException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("3 active reservations"));
        }
    }

    @Test
    public void inputChecksAndClosedPharmacy() throws Exception {
        batch(NAPA, "N", 50, 100);
        expectFailure(() -> service.reserve(patient(0), pharmacyId, NAPA, 11, MINUTES, null), "1 to 10 units");
        expectFailure(() -> service.reserve(patient(0), pharmacyId, NAPA, 1, 20, null), "30, 45 or 60");
        expectFailure(() -> service.reserve(ownerId, pharmacyId, NAPA, 1, MINUTES, null), "patient account");
        String open = DateUtil.nowPlusMinutes(120).substring(11, 16);
        String close = DateUtil.nowPlusMinutes(180).substring(11, 16);
        sql("UPDATE pharmacies SET open_time = '" + open + "', close_time = '" + close + "'");
        expectFailure(() -> service.reserve(patient(0), pharmacyId, NAPA, 1, MINUTES, null), "closed now");
    }

    @Test
    public void cancelGivesTheUnitsBack() throws Exception {
        int b = batch(SECLO, "S", 10, 100);
        ReservationView v = service.reserve(patient(2), pharmacyId, SECLO, 4, MINUTES, null);
        assertEquals(6, availableInSearch(SECLO));

        service.cancel(v.getReservation().getId(), patient(2));
        assertEquals(0, reserved(b));
        assertEquals(10, availableInSearch(SECLO));
        assertEquals(ReservationStatus.CANCELLED, service.getView(v.getReservation().getId()).getReservation().getStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE reason = 'RELEASED' AND change_qty = -4"));
        expectFailure(() -> service.cancel(v.getReservation().getId(), patient(2)), "already cancelled");
        expectFailure(() -> service.cancel(v.getReservation().getId(), patient(3)), "not found");
    }

    /** 10 patients press Reserve for 2 units at the same moment; only 5 units exist. */
    @Test
    public void simultaneousReservationsNeverTakeMoreUnitsThanExist() throws Exception {
        final int b = batch(SECLO, "LAST", 5, 100);
        final int threads = 10;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger refused = new AtomicInteger();
        final List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<Throwable>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int patient = patient(i);
            pool.submit(() -> {
                try {
                    start.await();
                    new ReservationService().reserve(patient, pharmacyId, SECLO, 2, MINUTES, null);
                    ok.incrementAndGet();
                } catch (ServiceException e) {
                    refused.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue("threads finished", pool.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue("unexpected errors: " + unexpected, unexpected.isEmpty());
        assertEquals("two reservations of 2 fit into 5 units", 2, ok.get());
        assertEquals(threads - 2, refused.get());
        assertEquals(4, reserved(b));
        assertEquals(2, count("SELECT COUNT(*) FROM reservations WHERE status = 'PENDING'"));
        assertEquals(4, count("SELECT COALESCE(SUM(quantity), 0) FROM reservations WHERE status = 'PENDING'"));
    }

    @Test
    public void reportIsSavedOnceAndAdminsAreNotified() throws Exception {
        batch(NAPA, "N", 50, 100);
        ReservationView v = service.reserve(patient(4), pharmacyId, NAPA, 1, MINUTES, null);
        ReportService reports = new ReportService();
        expectFailure(() -> reports.reportReservation(patient(4), v.getReservation().getId(), ReportReason.WRONG_STOCK, "short"),
                "at least 10");
        int before = count("SELECT COUNT(*) FROM notifications WHERE type = 'REPORT'");
        reports.reportReservation(patient(4), v.getReservation().getId(), ReportReason.WRONG_STOCK,
                "They said the medicine was not there.");
        assertEquals(1, count("SELECT COUNT(*) FROM reports WHERE status = 'OPEN' AND reason = 'WRONG_STOCK' AND pharmacy_id = "
                + pharmacyId));
        assertTrue(count("SELECT COUNT(*) FROM notifications WHERE type = 'REPORT'") > before);
        expectFailure(() -> reports.reportReservation(patient(4), v.getReservation().getId(), ReportReason.OTHER,
                "Second report about the same thing."), "already reported");
        expectFailure(() -> reports.reportReservation(patient(5), v.getReservation().getId(), ReportReason.OTHER,
                "Not my reservation at all."), "not found");
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

    private static int patient(int i) {
        return patients.get(i);
    }

    private static int availableInSearch(int medicineId) throws Exception {
        AvailabilityCache.clear();
        int sum = 0;
        for (medix.model.PharmacyStock s : new SearchService().search("", new medix.dao.MedicineDao().findById(medicineId),
                dhanmondi, 5.0, SearchEngine.Sort.DISTANCE).getRows()) {
            sum += s.getAvailable();
        }
        return sum;
    }

    private static int user(String email, Role role) throws Exception {
        User u = new User();
        u.setFullName(email);
        u.setEmail(email);
        u.setPhone("01711111111");
        u.setSalt("c2FsdA==");
        u.setPasswordHash("x");
        u.setRole(role);
        u.setAreaId(dhanmondi.getId());
        u.setActive(true);
        return new UserDao().insert(u);
    }

    private static int batch(int medicineId, String batchNo, int qty, int expiryDays) throws Exception {
        Inventory i = new Inventory();
        i.setPharmacyId(pharmacyId);
        i.setMedicineId(medicineId);
        i.setBatchNo(batchNo);
        i.setQuantity(qty);
        i.setPrice(2.5);
        i.setExpiryDate(DateUtil.todayPlusDays(expiryDays));
        i.setLowStockThreshold(1);
        return new InventoryDao().insert(i);
    }

    private static int reserved(int inventoryId) throws Exception {
        return count("SELECT reserved_qty FROM inventory WHERE id = " + inventoryId);
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
