package medix.service;

import java.sql.SQLException;
import java.util.List;
import medix.dao.NotificationDao;
import medix.dao.UserDao;
import medix.model.Notification;
import medix.model.NotificationType;
import medix.model.Role;
import medix.model.User;

/**
 * Creates and reads notifications. A notification can point at a record (link_entity +
 * link_id: "inventory", "reservations", "pharmacies", "reports") so "Open" can show it.
 */
public class NotificationService {

    public static final String LINK_INVENTORY = "inventory";
    public static final String LINK_RESERVATION = "reservations";
    public static final String LINK_PHARMACY = "pharmacies";
    public static final String LINK_REPORT = "reports";

    private final NotificationDao notificationDao = new NotificationDao();
    private final UserDao userDao = new UserDao();

    public int notifyUser(int userId, NotificationType type, String message) throws SQLException {
        return notifyUser(userId, type, message, null, null);
    }

    /** linkEntity / linkId may be null. Returns the new notification id. */
    public int notifyUser(int userId, NotificationType type, String message, String linkEntity, Integer linkId)
            throws SQLException {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setMessage(message);
        n.setRead(false);
        n.setLinkEntity(linkEntity);
        n.setLinkId(linkId);
        return notificationDao.insert(n);
    }

    /** Sends the message to every active admin. Returns how many admins were notified. */
    public int notifyAdmins(NotificationType type, String message) throws SQLException {
        return notifyAdmins(type, message, null, null);
    }

    public int notifyAdmins(NotificationType type, String message, String linkEntity, Integer linkId) throws SQLException {
        int count = 0;
        for (User admin : userDao.findByRole(Role.ADMIN)) {
            if (admin.isActive()) {
                notifyUser(admin.getId(), type, message, linkEntity, linkId);
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- reading (notification screens)

    /** The user's notifications of one type (null = all), newest first. */
    public List<Notification> getNotifications(int userId, NotificationType type) throws SQLException {
        return notificationDao.findByUser(userId, type);
    }

    public int countUnread(int userId) throws SQLException {
        return notificationDao.countUnread(userId);
    }

    public boolean markRead(int notificationId, int userId) throws SQLException {
        return notificationDao.markRead(notificationId, userId);
    }

    public int markAllRead(int userId) throws SQLException {
        return notificationDao.markAllRead(userId);
    }

    /** For toasts: notifications newer than lastSeenId, oldest first. */
    public List<Notification> getNewerThan(int userId, int lastSeenId) throws SQLException {
        return notificationDao.findNewerThan(userId, lastSeenId);
    }

    public int getLatestId(int userId) throws SQLException {
        return notificationDao.maxId(userId);
    }
}
