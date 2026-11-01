package medix.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.Notification;
import medix.model.NotificationType;
import medix.util.DateUtil;

/**
 * Data access for the notifications table.
 */
public class NotificationDao extends BaseDao<Notification> {

    @Override
    protected String tableName() {
        return "notifications";
    }

    @Override
    protected Notification mapRow(ResultSet rs) throws SQLException {
        Notification n = new Notification();
        n.setId(rs.getInt("id"));
        n.setUserId(rs.getInt("user_id"));
        n.setType(getEnum(rs, "type", NotificationType.class));
        n.setMessage(rs.getString("message"));
        n.setCreatedAt(rs.getString("created_at"));
        n.setRead(getBoolean(rs, "is_read"));
        n.setLinkEntity(rs.getString("link_entity"));
        n.setLinkId(getInteger(rs, "link_id"));
        return n;
    }

    public int insert(Notification n) throws SQLException {
        if (n.getCreatedAt() == null) {
            n.setCreatedAt(DateUtil.now());
        }
        int id = executeInsert(
                "INSERT INTO notifications (user_id, type, message, created_at, is_read, link_entity, link_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                n.getUserId(), n.getType(), n.getMessage(), n.getCreatedAt(), n.isRead(), n.getLinkEntity(),
                n.getLinkId());
        n.setId(id);
        return id;
    }

    public boolean update(Notification n) throws SQLException {
        return executeUpdate(
                "UPDATE notifications SET user_id = ?, type = ?, message = ?, is_read = ?, link_entity = ?, link_id = ? "
                + "WHERE id = ?",
                n.getUserId(), n.getType(), n.getMessage(), n.isRead(), n.getLinkEntity(), n.getLinkId(),
                n.getId()) > 0;
    }

    public List<Notification> findByUser(int userId) throws SQLException {
        return findList("SELECT * FROM notifications WHERE user_id = ? ORDER BY id DESC", userId);
    }

    /** The user's notifications of one type (null = all), newest first. */
    public List<Notification> findByUser(int userId, NotificationType type) throws SQLException {
        if (type == null) {
            return findByUser(userId);
        }
        return findList("SELECT * FROM notifications WHERE user_id = ? AND type = ? ORDER BY id DESC", userId, type);
    }

    /** Notifications newer than lastSeenId (for toasts), oldest first. */
    public List<Notification> findNewerThan(int userId, int lastSeenId) throws SQLException {
        return findList("SELECT * FROM notifications WHERE user_id = ? AND id > ? ORDER BY id", userId, lastSeenId);
    }

    /** Highest notification id of the user (0 when none). */
    public int maxId(int userId) throws SQLException {
        return queryInt("SELECT COALESCE(MAX(id), 0) FROM notifications WHERE user_id = ?", userId);
    }

    /** True if the user already got a notification of this type starting with the prefix since the time. */
    public boolean existsSince(int userId, NotificationType type, String messagePrefix, String since)
            throws SQLException {
        return queryInt("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ? "
                + "AND substr(message, 1, ?) = ? AND created_at >= ?",
                userId, type, messagePrefix.length(), messagePrefix, since) > 0;
    }

    /** True if the user already got a notification of this type about this record since the time. */
    public boolean existsForLinkSince(int userId, NotificationType type, String linkEntity, int linkId, String since)
            throws SQLException {
        return queryInt("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ? AND link_entity = ? "
                + "AND link_id = ? AND created_at >= ?", userId, type, linkEntity, linkId, since) > 0;
    }

    public int countUnread(int userId) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0", userId);
    }

    /** Marks one of the user's notifications read; false if it is not theirs. */
    public boolean markRead(int id, int userId) throws SQLException {
        return executeUpdate("UPDATE notifications SET is_read = 1 WHERE id = ? AND user_id = ?", id, userId) > 0;
    }

    public int markAllRead(int userId) throws SQLException {
        return executeUpdate("UPDATE notifications SET is_read = 1 WHERE user_id = ? AND is_read = 0", userId);
    }
}
