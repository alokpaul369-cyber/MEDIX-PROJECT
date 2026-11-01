package medix.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.AuditLog;
import medix.model.AuditLogView;
import medix.model.NamedCount;
import medix.util.DateUtil;

/**
 * Data access for the audit_logs table.
 */
public class AuditLogDao extends BaseDao<AuditLog> {

    @Override
    protected String tableName() {
        return "audit_logs";
    }

    @Override
    protected AuditLog mapRow(ResultSet rs) throws SQLException {
        AuditLog a = new AuditLog();
        a.setId(rs.getInt("id"));
        a.setUserId(getInteger(rs, "user_id"));
        a.setAction(rs.getString("action"));
        a.setEntity(rs.getString("entity"));
        a.setEntityId(getInteger(rs, "entity_id"));
        a.setDetails(rs.getString("details"));
        a.setCreatedAt(rs.getString("created_at"));
        return a;
    }

    public int insert(AuditLog a) throws SQLException {
        if (a.getCreatedAt() == null) {
            a.setCreatedAt(DateUtil.now());
        }
        int id = executeInsert(
                "INSERT INTO audit_logs (user_id, action, entity, entity_id, details, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                a.getUserId(), a.getAction(), a.getEntity(), a.getEntityId(), a.getDetails(), a.getCreatedAt());
        a.setId(id);
        return id;
    }

    public boolean update(AuditLog a) throws SQLException {
        return executeUpdate(
                "UPDATE audit_logs SET user_id = ?, action = ?, entity = ?, entity_id = ?, details = ? "
                + "WHERE id = ?",
                a.getUserId(), a.getAction(), a.getEntity(), a.getEntityId(), a.getDetails(), a.getId()) > 0;
    }

    /** True if this action was logged at or after `since` (yyyy-MM-dd HH:mm:ss). */
    public boolean existsSince(String action, String since) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM audit_logs WHERE action = ? AND created_at >= ?", action, since) > 0;
    }

    /** Newest entries first. */
    public List<AuditLog> findRecent(int limit) throws SQLException {
        return findList("SELECT * FROM audit_logs ORDER BY id DESC LIMIT ?", limit);
    }

    private static final String FILTER = "FROM audit_logs a LEFT JOIN users u ON u.id = a.user_id "
            + "WHERE (? IS NULL OR (? = 0 AND a.user_id IS NULL) OR a.user_id = ?) "
            + "AND (? IS NULL OR a.action = ?) "
            + "AND (? IS NULL OR a.created_at >= ?) AND (? IS NULL OR a.created_at < ?) ";

    private static Object[] filterParams(Integer userId, String action, String from, String to) {
        return new Object[]{userId, userId, userId, action, action, from, from, to, to};
    }

    /**
     * Entries matching the filters, newest first, at most `limit`. userId null = everyone,
     * 0 = no user (system jobs, failed logins); action null = any; from / to (date-times,
     * [from, to)) null = open.
     */
    public List<AuditLogView> search(Integer userId, String action, String from, String to, int limit)
            throws SQLException {
        Object[] filter = filterParams(userId, action, from, to);
        Object[] params = new Object[filter.length + 1];
        System.arraycopy(filter, 0, params, 0, filter.length);
        params[filter.length] = limit;
        return query("SELECT a.*, u.full_name AS user_name " + FILTER + "ORDER BY a.id DESC LIMIT ?",
                rs -> new AuditLogView(mapRow(rs), rs.getString("user_name") == null ? "System" : rs.getString("user_name")),
                params);
    }

    /** How many entries match the filters (without the limit). */
    public int count(Integer userId, String action, String from, String to) throws SQLException {
        return queryInt("SELECT COUNT(*) " + FILTER, filterParams(userId, action, from, to));
    }

    /** Every action name that was logged, A to Z. */
    public List<String> findActions() throws SQLException {
        return query("SELECT DISTINCT action FROM audit_logs ORDER BY action", rs -> rs.getString("action"));
    }

    /** Users that appear in the log (id + name), A to Z. */
    public List<NamedCount> findLoggedUsers() throws SQLException {
        return query("SELECT u.id, u.full_name, u.email, COUNT(*) AS n FROM audit_logs a JOIN users u ON u.id = a.user_id "
                + "GROUP BY u.id ORDER BY u.full_name",
                rs -> new NamedCount(rs.getInt("id"), rs.getString("full_name") + " (" + rs.getString("email") + ")",
                        rs.getInt("n")));
    }
}
