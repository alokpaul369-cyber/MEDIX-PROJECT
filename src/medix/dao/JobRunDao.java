package medix.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.JobRun;
import medix.model.JobRunSummary;

/**
 * Data access for the job_runs table.
 */
public class JobRunDao extends BaseDao<JobRun> {

    @Override
    protected String tableName() {
        return "job_runs";
    }

    @Override
    protected JobRun mapRow(ResultSet rs) throws SQLException {
        JobRun j = new JobRun();
        j.setId(rs.getInt("id"));
        j.setJobName(rs.getString("job_name"));
        j.setStartedAt(rs.getString("started_at"));
        j.setFinishedAt(rs.getString("finished_at"));
        j.setStatus(rs.getString("status"));
        j.setItemsAffected(rs.getInt("items_affected"));
        j.setMessage(rs.getString("message"));
        return j;
    }

    public int insert(JobRun j) throws SQLException {
        int id = executeInsert("INSERT INTO job_runs (job_name, started_at, finished_at, status, items_affected, message) "
                + "VALUES (?, ?, ?, ?, ?, ?)", j.getJobName(), j.getStartedAt(), j.getFinishedAt(), j.getStatus(),
                j.getItemsAffected(), j.getMessage());
        j.setId(id);
        return id;
    }

    public boolean update(JobRun j) throws SQLException {
        return executeUpdate("UPDATE job_runs SET job_name = ?, started_at = ?, finished_at = ?, status = ?, "
                + "items_affected = ?, message = ? WHERE id = ?", j.getJobName(), j.getStartedAt(), j.getFinishedAt(),
                j.getStatus(), j.getItemsAffected(), j.getMessage(), j.getId()) > 0;
    }

    /** True if the job finished OK at or after `since` (used to catch up daily jobs missed while closed). */
    public boolean succeededSince(String jobName, String since) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM job_runs WHERE job_name = ? AND status = 'OK' AND started_at >= ?",
                jobName, since) > 0;
    }

    /** One row per job: its latest run, how often it ran and how often it failed; by job name. */
    public List<JobRunSummary> findLatestPerJob() throws SQLException {
        return query("SELECT j.*, (SELECT COUNT(*) FROM job_runs c WHERE c.job_name = j.job_name) AS runs, "
                + "(SELECT COUNT(*) FROM job_runs f WHERE f.job_name = j.job_name AND f.status = 'FAILED') AS failures "
                + "FROM job_runs j WHERE j.id = (SELECT MAX(id) FROM job_runs m WHERE m.job_name = j.job_name) "
                + "ORDER BY j.job_name",
                rs -> new JobRunSummary(mapRow(rs), rs.getInt("runs"), rs.getInt("failures")));
    }

    /** Newest runs first (history). */
    public List<JobRun> findRecent(int limit) throws SQLException {
        return findList("SELECT * FROM job_runs ORDER BY id DESC LIMIT ?", limit);
    }
}
