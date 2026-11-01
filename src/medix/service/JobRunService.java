package medix.service;

import java.sql.SQLException;
import java.util.List;
import medix.dao.JobRunDao;
import medix.model.JobRun;
import medix.model.JobRunSummary;
import medix.model.NotificationType;
import medix.util.DateUtil;
import medix.util.ErrorLog;

/**
 * job_runs: the history of the background jobs (written by JobScheduler, shown in AlertsFrame).
 */
public class JobRunService {

    private final JobRunDao jobRunDao = new JobRunDao();
    private final NotificationService notificationService = new NotificationService();

    /** Writes one job_runs row. Never throws: a failed write is printed. */
    public JobRun record(String jobName, String startedAt, String status, int itemsAffected, String message) {
        JobRun run = new JobRun();
        run.setJobName(jobName);
        run.setStartedAt(startedAt);
        run.setFinishedAt(DateUtil.now());
        run.setStatus(status);
        run.setItemsAffected(itemsAffected);
        run.setMessage(message);
        try {
            jobRunDao.insert(run);
        } catch (SQLException e) {
            ErrorLog.log("Could not write job_runs for " + jobName, e);
        }
        return run;
    }

    /** "Job Expiry sweep failed: <message>" to every admin. Never throws. */
    public void notifyAdminsOfFailure(String jobName, String message) {
        try {
            notificationService.notifyAdmins(NotificationType.INFO, "Job " + jobName + " failed: " + message);
        } catch (SQLException e) {
            ErrorLog.log("Could not notify admins about job " + jobName, e);
        }
    }

    /** True if the job finished OK today (daily jobs that were missed while the app was closed run at start-up). */
    public boolean succeededToday(String jobName) {
        try {
            return jobRunDao.succeededSince(jobName, DateUtil.today() + " 00:00:00");
        } catch (SQLException e) {
            return false;
        }
    }

    public List<JobRunSummary> getLatestPerJob() throws SQLException {
        return jobRunDao.findLatestPerJob();
    }
}
