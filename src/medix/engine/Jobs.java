package medix.engine;

import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

/**
 * The timetable of all background jobs. Main calls registerAll() once, after the database is ready.
 */
public final class Jobs {

    public static final LocalTime TRUST_TIME = LocalTime.of(2, 0);
    public static final LocalTime CLEANUP_TIME = LocalTime.of(3, 0);
    public static final LocalTime EXPIRY_SWEEP_TIME = LocalTime.of(8, 0);

    private Jobs() {
    }

    public static void registerAll() {
        JobScheduler.start();
        JobScheduler.every(new ReservationExpiryJob(), 10, 60, TimeUnit.SECONDS);
        JobScheduler.every(new LowStockJob(), 30, 15 * 60, TimeUnit.SECONDS);
        JobScheduler.every(new StaleStockJob(), 2 * 60, 60 * 60, TimeUnit.SECONDS);
        JobScheduler.dailyAt(new ExpirySweepJob(), EXPIRY_SWEEP_TIME, true);
        JobScheduler.dailyAt(new TrustScoreJob(), TRUST_TIME, true);
        JobScheduler.dailyAt(new SearchLogCleanupJob(), CLEANUP_TIME, true);
    }
}
