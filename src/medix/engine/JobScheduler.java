package medix.engine;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import medix.model.JobRun;
import medix.service.JobRunService;
import medix.util.DateUtil;
import medix.util.ErrorLog;

/**
 * Background jobs (they replace Spring @Scheduled / Quartz from the report): one
 * ScheduledExecutorService with 2 daemon threads, started in Main after the database is ready
 * and stopped on exit.
 *
 * Every run goes through {@link #runNow}: try/catch, up to 3 retries 30 seconds apart, then one
 * job_runs row (OK or FAILED); after the last failed retry the admins get "Job &lt;name&gt; failed:
 * &lt;message&gt;". The same job never runs twice at the same time. Jobs must not touch Swing
 * components or Session; UI updates go through SwingUtilities.invokeLater.
 */
public final class JobScheduler {

    /** A background job. run() returns how many items it handled (stored in job_runs). */
    public interface Job {
        String getName();

        int run() throws Exception;
    }

    public static final int THREADS = 2;
    public static final int MAX_RETRIES = 3;
    private static long retryDelayMillis = 30 * 1000L;

    private static ScheduledExecutorService executor;
    private static final Set<String> RUNNING = Collections.synchronizedSet(new HashSet<String>());
    private static final JobRunService RUNS = new JobRunService();

    private JobScheduler() {
    }

    public static synchronized void start() {
        if (executor != null) {
            return;
        }
        final AtomicInteger n = new AtomicInteger();
        executor = Executors.newScheduledThreadPool(THREADS, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "medix-jobs-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public static synchronized boolean isRunning() {
        return executor != null;
    }

    /** Runs the job every `period` (first run after `initialDelay`). */
    public static synchronized ScheduledFuture<?> every(final Job job, long initialDelay, long period, TimeUnit unit) {
        start();
        return executor.scheduleWithFixedDelay(() -> runNow(job), initialDelay, period, unit);
    }

    /**
     * Runs the job every day at `time`. With catchUp, a job that has not finished OK today and
     * whose time has already passed also runs once, one minute after start-up (a desktop app is
     * often closed at night).
     */
    public static synchronized void dailyAt(final Job job, LocalTime time, boolean catchUp) {
        start();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(time);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long delay = Duration.between(now, next).getSeconds();
        executor.scheduleAtFixedRate(() -> runNow(job), delay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
        if (catchUp && !now.toLocalTime().isBefore(time) && !RUNS.succeededToday(job.getName())) {
            executor.schedule(() -> runNow(job), 1, TimeUnit.MINUTES);
        }
    }

    /** A plain repeating task that is not a recorded job (e.g. the 30-second notification check). */
    public static synchronized ScheduledFuture<?> repeat(final Runnable task, long initialDelay, long period, TimeUnit unit) {
        start();
        return executor.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                ErrorLog.log("Background task failed", e);
            }
        }, initialDelay, period, unit);
    }

    /**
     * Runs the job on the calling thread with retries and records the result in job_runs.
     * Returns the recorded run, or null when the job was already running.
     */
    public static JobRun runNow(Job job) {
        if (!RUNNING.add(job.getName())) {
            return null;
        }
        String started = DateUtil.now();
        try {
            Exception last = null;
            for (int attempt = 1; attempt <= 1 + MAX_RETRIES; attempt++) {
                try {
                    int items = job.run();
                    return RUNS.record(job.getName(), started, JobRun.OK, items,
                            attempt == 1 ? "OK" : "OK after " + attempt + " attempts");
                } catch (Exception e) {
                    last = e;
                    ErrorLog.log("Job " + job.getName() + " attempt " + attempt + " failed", e);
                    if (attempt <= MAX_RETRIES && !sleep(retryDelayMillis)) {
                        break; // stopping
                    }
                }
            }
            String message = last == null ? "stopped" : (last.getMessage() == null ? last.toString() : last.getMessage());
            JobRun run = RUNS.record(job.getName(), started, JobRun.FAILED, 0, message);
            RUNS.notifyAdminsOfFailure(job.getName(), message);
            return run;
        } finally {
            RUNNING.remove(job.getName());
        }
    }

    /** Tests use a short delay; the app keeps 30 seconds. */
    public static void setRetryDelayMillis(long millis) {
        retryDelayMillis = millis;
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
