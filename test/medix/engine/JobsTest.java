package medix.engine;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import medix.dao.AreaDao;
import medix.dao.InventoryDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.model.Area;
import medix.model.Inventory;
import medix.model.JobRun;
import medix.model.JobRunSummary;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.ReservationStatus;
import medix.model.ReservationView;
import medix.model.Role;
import medix.model.User;
import medix.service.DatabaseService;
import medix.service.JobRunService;
import medix.service.ReservationService;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.Session;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The background jobs and JobScheduler's retry / job_runs handling, on a temporary database.
 * Seeded medicine 5 = Seclo 20 mg.
 */
public class JobsTest {

    private static final int SECLO = 5;

    private static Area dhanmondi;
    private static User owner;
    private static Pharmacy pharmacy;
    private static Pharmacy pendingPharmacy;
    private static int patient;

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-jobs-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        JobScheduler.setRetryDelayMillis(0);
        dhanmondi = new AreaDao().findByName("Dhanmondi");
        owner = user("owner@test.com", Role.PHARMACY);
        pharmacy = pharmacy(owner, "Green Life Pharmacy", "DL-111222", PharmacyStatus.VERIFIED);
        pendingPharmacy = pharmacy(user("pending@test.com", Role.PHARMACY), "Pending Pharmacy", "DL-333444",
                PharmacyStatus.PENDING);
        patient = user("patient@test.com", Role.PATIENT).getId();
    }

    @Before
    public void reset() throws Exception {
        Session.logout();
        sql("DELETE FROM reservations");
        sql("DELETE FROM inventory");
        sql("DELETE FROM notifications");
        sql("DELETE FROM job_runs");
        sql("DELETE FROM search_logs");
    }

    @Test
    public void databaseHasTwelveTablesIncludingJobRuns() throws Exception {
        assertEquals(12, new DatabaseService().getTables().size());
        assertEquals("job_runs", new DatabaseService().getTables().get(11).getName());
        assertEquals(1, count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_job_runs_job_name'"));
    }

    @Test
    public void reservationLeftAloneExpiresAndGivesTheStockBack() throws Exception {
        int b = batch(pharmacy, "SC-2510", 10, 10, 100);
        ReservationView old = new ReservationService().reserve(patient, pharmacy.getId(), SECLO, 4, 30, null);
        ReservationView running = new ReservationService().reserve(user("p2@test.com", Role.PATIENT).getId(),
                pharmacy.getId(), SECLO, 2, 30, null);
        sql("UPDATE reservations SET expires_at = datetime('now', 'localtime', '-1 minutes') WHERE id = "
                + old.getReservation().getId());
        assertEquals(6, count("SELECT reserved_qty FROM inventory WHERE id = " + b));

        JobRun run = JobScheduler.runNow(new ReservationExpiryJob());
        assertEquals(JobRun.OK, run.getStatus());
        assertEquals(1, run.getItemsAffected());
        assertEquals(ReservationStatus.EXPIRED, status(old.getReservation().getId()));
        assertEquals(ReservationStatus.PENDING, status(running.getReservation().getId()));
        assertEquals("only the running hold keeps units", 2, count("SELECT reserved_qty FROM inventory WHERE id = " + b));
        assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE reason = 'RELEASED' AND change_qty = -4 "
                + "AND user_id IS NULL AND note LIKE '%expired%'"));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + patient
                + " AND link_entity = 'reservations' AND message LIKE 'Your reservation % expired at %'"));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + owner.getId()
                + " AND message LIKE 'Reservation % expired without pickup. 4 unit(s) are available again.'"));
        assertEquals(1, count("SELECT COUNT(*) FROM job_runs WHERE job_name = 'Reservation expiry' AND status = 'OK'"));

        assertEquals(0, JobScheduler.runNow(new ReservationExpiryJob()).getItemsAffected());
    }

    @Test
    public void lowStockAlertOncePerBatchPerDay() throws Exception {
        int b = batch(pharmacy, "SC-2510", 2, 10, 100);
        batch(pharmacy, "SC-OLD", 1, 10, -5);           // expired: not a low-stock alert
        batch(pharmacy, "SC-OK", 50, 10, 100);           // plenty
        batch(pendingPharmacy, "P-1", 1, 10, 100);       // not verified
        assertEquals(1, JobScheduler.runNow(new LowStockJob()).getItemsAffected());
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + owner.getId() + " AND type = 'LOW_STOCK' "
                + "AND message = 'Seclo 20 mg (batch SC-2510) has 2 left. Alert level is 10.' "
                + "AND link_entity = 'inventory' AND link_id = " + b));
        assertEquals("not again the same day", 0, JobScheduler.runNow(new LowStockJob()).getItemsAffected());
    }

    @Test
    public void expirySweepWarnsAboutExpiringAndExpiredBatches() throws Exception {
        batch(pharmacy, "SC-45", 30, 1, 45);
        batch(pharmacy, "SC-X", 40, 1, -3);
        batch(pharmacy, "SC-FAR", 30, 1, 100);           // not within 60 days
        int empty = batch(pharmacy, "SC-EMPTY", 5, 1, -3);
        sql("UPDATE inventory SET quantity = 0 WHERE id = " + empty);   // nothing left to remove
        assertEquals(2, JobScheduler.runNow(new ExpirySweepJob()).getItemsAffected());
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE type = 'EXPIRY' "
                + "AND message = 'Seclo 20 mg (batch SC-45) expires in 45 days: 30 units.'"));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE type = 'EXPIRY' "
                + "AND message = 'Seclo 20 mg (batch SC-X) has expired. Remove 40 units from the shelf.'"));
        assertEquals(0, JobScheduler.runNow(new ExpirySweepJob()).getItemsAffected());
    }

    @Test
    public void staleStockOncePerDay() throws Exception {
        int b = batch(pharmacy, "SC-1", 10, 1, 100);
        assertEquals(0, JobScheduler.runNow(new StaleStockJob()).getItemsAffected());
        sql("UPDATE inventory SET updated_at = datetime('now', 'localtime', '-2 days') WHERE id = " + b);
        assertEquals(1, JobScheduler.runNow(new StaleStockJob()).getItemsAffected());
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE type = 'STALE' AND user_id = " + owner.getId()
                + " AND message LIKE 'Your stock was last updated 2 d ago.%'"));
        assertEquals(0, JobScheduler.runNow(new StaleStockJob()).getItemsAffected());
    }

    @Test
    public void searchLogsOlderThan90DaysAreDeleted() throws Exception {
        sql("INSERT INTO search_logs (query, area_id, results_count, created_at) VALUES ('old', 1, 0, datetime('now','localtime','-100 days'))");
        sql("INSERT INTO search_logs (query, area_id, results_count, created_at) VALUES ('recent', 1, 0, datetime('now','localtime','-10 days'))");
        assertEquals(1, JobScheduler.runNow(new SearchLogCleanupJob()).getItemsAffected());
        assertEquals(1, count("SELECT COUNT(*) FROM search_logs WHERE query = 'recent'"));
    }

    @Test
    public void failingJobIsRetriedThreeTimesThenFailedAndAdminsAreTold() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        JobRun run = JobScheduler.runNow(job("Flaky", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertEquals(1 + JobScheduler.MAX_RETRIES, attempts.get());
        assertEquals(JobRun.FAILED, run.getStatus());
        assertEquals("boom", run.getMessage());
        assertEquals(1, count("SELECT COUNT(*) FROM job_runs WHERE job_name = 'Flaky' AND status = 'FAILED'"));
        assertTrue(count("SELECT COUNT(*) FROM notifications n JOIN users u ON u.id = n.user_id "
                + "WHERE u.role = 'ADMIN' AND n.message = 'Job Flaky failed: boom'") >= 1);
    }

    @Test
    public void jobThatRecoversIsOkAfterRetries() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        JobRun run = JobScheduler.runNow(job("Recovering", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("not yet");
            }
            return 7;
        }));
        assertEquals(JobRun.OK, run.getStatus());
        assertEquals(7, run.getItemsAffected());
        assertEquals("OK after 3 attempts", run.getMessage());
    }

    @Test
    public void historyShowsTheLatestRunPerJobAndTheJobNeverOverlaps() throws Exception {
        JobScheduler.runNow(new LowStockJob());
        JobScheduler.runNow(new LowStockJob());
        JobScheduler.runNow(new StaleStockJob());
        List<JobRunSummary> latest = new JobRunService().getLatestPerJob();
        assertEquals(2, latest.size());
        assertEquals("Low stock", latest.get(0).getLast().getJobName());
        assertEquals(2, latest.get(0).getRuns());
        assertNotNull(latest.get(0).getLast().getFinishedAt());

        final JobRun[] inner = new JobRun[1];
        final JobScheduler.Job[] self = new JobScheduler.Job[1];
        self[0] = job("Slow", () -> {
            inner[0] = JobScheduler.runNow(self[0]);   // the same job again while it runs
            return 0;
        });
        JobScheduler.runNow(self[0]);
        assertNull("a job never runs twice at the same time", inner[0]);
    }

    // ---------------------------------------------------------------- helpers

    private interface Body {
        int run() throws Exception;
    }

    private static JobScheduler.Job job(final String name, final Body body) {
        return new JobScheduler.Job() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public int run() throws Exception {
                return body.run();
            }
        };
    }

    private static ReservationStatus status(int id) throws Exception {
        return new ReservationService().getView(id).getReservation().getStatus();
    }

    private static User user(String email, Role role) throws Exception {
        User u = new User();
        u.setFullName(email);
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

    private static Pharmacy pharmacy(User o, String name, String license, PharmacyStatus status) throws Exception {
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
        p.setStatus(status);
        new PharmacyDao().insert(p);
        return p;
    }

    private static int batch(Pharmacy p, String batchNo, int qty, int threshold, int expiryDays) throws Exception {
        Inventory i = new Inventory();
        i.setPharmacyId(p.getId());
        i.setMedicineId(SECLO);
        i.setBatchNo(batchNo);
        i.setQuantity(qty);
        i.setPrice(7.0);
        i.setExpiryDate(DateUtil.todayPlusDays(expiryDays));
        i.setLowStockThreshold(threshold);
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
