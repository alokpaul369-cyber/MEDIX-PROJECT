package medix.util;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import medix.forms.InventoryFrame;
import medix.forms.MyReservationsFrame;
import medix.forms.PharmacyProfileFrame;
import medix.forms.PharmacyReservationsFrame;
import medix.forms.ReportsFrame;
import medix.forms.VerificationFrame;
import medix.model.Notification;
import medix.model.NotificationType;
import medix.model.Role;
import medix.service.NotificationService;

/**
 * "Open" on a notification: shows the related screen with the record selected and disposes the
 * current frame (navigation rule). Uses link_entity / link_id of the notification.
 */
public final class NotificationNavigator {

    private NotificationNavigator() {
    }

    /** The screen for the notification, or null when there is nothing to open. */
    public static JFrame target(Notification n) {
        Role role = Session.getRole();
        String link = n.getLinkEntity();
        Integer id = n.getLinkId();
        if (role == Role.PHARMACY) {
            if (NotificationService.LINK_INVENTORY.equals(link) && id != null) {
                return new InventoryFrame(id);
            }
            if (NotificationService.LINK_RESERVATION.equals(link) && id != null) {
                return new PharmacyReservationsFrame(id);
            }
            if (n.getType() == NotificationType.STALE || n.getType() == NotificationType.LOW_STOCK
                    || n.getType() == NotificationType.EXPIRY) {
                return new InventoryFrame();
            }
            if (n.getType() == NotificationType.VERIFICATION) {
                return new PharmacyProfileFrame();
            }
        } else if (role == Role.PATIENT) {
            if (NotificationService.LINK_RESERVATION.equals(link) && id != null) {
                return new MyReservationsFrame(id);
            }
        } else if (role == Role.ADMIN) {
            if (NotificationService.LINK_PHARMACY.equals(link) && id != null) {
                return new VerificationFrame(id);
            }
            if (NotificationService.LINK_REPORT.equals(link) && id != null) {
                return new ReportsFrame(id);
            }
            if (n.getType() == NotificationType.REPORT) {
                return new ReportsFrame();
            }
        }
        return null;
    }

    /** True when target() would open something. */
    public static boolean canOpen(Notification n) {
        Role role = Session.getRole();
        String link = n.getLinkEntity();
        if (role == Role.PHARMACY) {
            return NotificationService.LINK_INVENTORY.equals(link) || NotificationService.LINK_RESERVATION.equals(link)
                    || n.getType() == NotificationType.STALE || n.getType() == NotificationType.LOW_STOCK
                    || n.getType() == NotificationType.EXPIRY || n.getType() == NotificationType.VERIFICATION;
        }
        if (role == Role.PATIENT) {
            return NotificationService.LINK_RESERVATION.equals(link) && n.getLinkId() != null;
        }
        return role == Role.ADMIN && ((NotificationService.LINK_PHARMACY.equals(link) && n.getLinkId() != null)
                || NotificationService.LINK_REPORT.equals(link) || n.getType() == NotificationType.REPORT);
    }

    /** Opens the related screen (closing `current`), or explains why there is nothing to open. */
    public static void open(JFrame current, Notification n) {
        JFrame next = target(n);
        if (next == null) {
            JOptionPane.showMessageDialog(current, "There is no screen to open for this notification.",
                    current.getTitle(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        next.setVisible(true);
        current.dispose();
    }
}
