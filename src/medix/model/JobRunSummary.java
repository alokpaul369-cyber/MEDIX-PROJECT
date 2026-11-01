package medix.model;

/**
 * A job with its number of runs and its latest run (for the jobs table). Not a table.
 */
public class JobRunSummary {

    private final JobRun last;
    private final int runs;
    private final int failures;

    public JobRunSummary(JobRun last, int runs, int failures) {
        this.last = last;
        this.runs = runs;
        this.failures = failures;
    }

    public JobRun getLast() {
        return last;
    }

    public int getRuns() {
        return runs;
    }

    public int getFailures() {
        return failures;
    }
}
