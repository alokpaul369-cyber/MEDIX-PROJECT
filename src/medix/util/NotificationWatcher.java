package medix.util;

import java.awt.AWTException;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import medix.engine.JobScheduler;
import medix.forms.ToastFrame;
import medix.model.Notification;
import medix.model.NotificationType;
import medix.service.NotificationService;

/**
 * While someone is logged in, checks every 30 seconds (on a job thread) for notifications newer
 * than the last one seen. New ones pop up as ToastFrames (at most 3 at once, then a summary),
 * as a system tray message when the tray is supported, and update the sidebar's unread badge.
 * All Swing work goes through SwingUtilities.invokeLater.
 *
 * LoginFrame calls start() after login; logout calls stop().
 */
public final class NotificationWatcher {

    public static final int CHECK_SECONDS = 30;
    private static final int MAX_TOASTS_AT_ONCE = 3;

    private static ScheduledFuture<?> task;
    private static volatile Integer watchedUser;
    private static volatile int lastSeenId;
    private static TrayIcon trayIcon;

    private NotificationWatcher() {
    }

    /** Starts watching for the logged-in user; shows one toast if there are unread notifications. */
    public static synchronized void start() {
        stop();
        Integer me = Session.getUserId();
        if (me == null) {
            return;
        }
        watchedUser = me;
        NotificationService service = new NotificationService();
        int unread = 0;
        try {
            lastSeenId = service.getLatestId(me);
            unread = service.countUnread(me);
        } catch (SQLException e) {
            ErrorLog.log("Notification watcher", e);
        }
        if (unread > 0) {
            final int n = unread;
            SwingUtilities.invokeLater(() -> ToastFrame.pop("Notifications",
                    "You have " + n + " unread notification" + (n == 1 ? "" : "s") + ".", UITheme.ACCENT));
        }
        if (JobScheduler.isRunning()) {
            task = JobScheduler.repeat(NotificationWatcher::check, CHECK_SECONDS, CHECK_SECONDS, TimeUnit.SECONDS);
        }
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        watchedUser = null;
    }

    /** One check (runs on a job thread). */
    static void check() {
        Integer me = Session.getUserId();
        if (me == null || !me.equals(watchedUser)) {
            return;
        }
        NotificationService service = new NotificationService();
        final List<Notification> fresh;
        final int unread;
        try {
            fresh = service.getNewerThan(me, lastSeenId);
            unread = service.countUnread(me);
        } catch (SQLException e) {
            ErrorLog.log("Notification check failed", e);
            return;
        }
        if (!fresh.isEmpty()) {
            lastSeenId = fresh.get(fresh.size() - 1).getId();
        }
        SwingUtilities.invokeLater(() -> {
            SidebarUtil.updateBadge(unread);
            showNew(fresh);
        });
    }

    private static void showNew(List<Notification> fresh) {
        if (fresh.isEmpty()) {
            return;
        }
        int shown = 0;
        for (int i = fresh.size() - 1; i >= 0 && shown < MAX_TOASTS_AT_ONCE - (fresh.size() > MAX_TOASTS_AT_ONCE ? 1 : 0); i--) {
            Notification n = fresh.get(i);
            ToastFrame.pop(n.getType().toString(), n.getMessage(), UITheme.notificationTypeColor(n.getType(), n.getMessage()));
            shown++;
        }
        if (fresh.size() > MAX_TOASTS_AT_ONCE) {
            ToastFrame.pop("Notifications", "+" + (fresh.size() - shown) + " more new notifications.", UITheme.ACCENT);
        }
        Notification newest = fresh.get(fresh.size() - 1);
        trayMessage(newest.getType(), fresh.size() == 1 ? newest.getMessage()
                : fresh.size() + " new notifications. Latest: " + newest.getMessage());
    }

    /** SystemTray message, when the system supports a tray. */
    private static synchronized void trayMessage(NotificationType type, String text) {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            if (trayIcon == null) {
                trayIcon = new TrayIcon(UITheme.createLogoImage(16), "MediX");
                trayIcon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(trayIcon);
            }
            trayIcon.displayMessage("MediX – " + type, text, TrayIcon.MessageType.INFO);
        } catch (AWTException | SecurityException e) {
            ErrorLog.log("Tray message failed", e);
        }
    }

    /** Removes the tray icon (logout / exit). */
    public static synchronized void removeTrayIcon() {
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (RuntimeException e) {
                // the system tray may already be gone at shutdown
            }
            trayIcon = null;
        }
    }
}
