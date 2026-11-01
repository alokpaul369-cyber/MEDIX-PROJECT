package medix.model;

/**
 * Row of the job_runs table: one run of a background job (JobScheduler).
 */
public class JobRun {

    public static final String OK = "OK";
    public static final String FAILED = "FAILED";

    private int id;
    private String jobName;
    private String startedAt;
    private String finishedAt;
    /** OK or FAILED. */
    private String status;
    private int itemsAffected;
    private String message;

    public JobRun() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getItemsAffected() {
        return itemsAffected;
    }

    public void setItemsAffected(int itemsAffected) {
        this.itemsAffected = itemsAffected;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isOk() {
        return OK.equals(status);
    }

    @Override
    public String toString() {
        return jobName;
    }
}
