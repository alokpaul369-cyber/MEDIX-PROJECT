package medix.util;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import medix.forms.AdminDashboardFrame;
import medix.forms.AlertsFrame;
import medix.forms.AuditLogFrame;
import medix.forms.LoginFrame;
import medix.forms.MedicineCatalogFrame;
import medix.forms.MyReservationsFrame;
import medix.forms.NotificationFrame;
import medix.forms.SearchFrame;
import medix.forms.InventoryFrame;
import medix.forms.PharmacyProfileFrame;
import medix.forms.PharmacyReservationsFrame;
import medix.forms.ProfileFrame;
import medix.forms.ReportsFrame;
import medix.forms.UsersFrame;
import medix.forms.VerificationFrame;
import medix.model.Pharmacy;
import medix.model.Role;
import medix.service.AuditService;
import medix.service.AuthService;
import medix.service.NotificationService;
import medix.service.ReportService;

/**
 * Builds the left menu (pnlSidebar) of every logged-in frame for the user's role.
 * Each item opens its JFrame Form and disposes the current one.
 *
 * When a later part builds a screen, replace the {@code null} for its item in
 * {@link #menuFor(Role)} with a {@code SomeFrame::new} constructor reference.
 */
public final class SidebarUtil {

    public static final String PATIENT_SEARCH = "Search Medicine";
    public static final String PATIENT_RESERVATIONS = "My Reservations";
    public static final String PATIENT_NOTIFICATIONS = "Notifications";
    public static final String PATIENT_PROFILE = "Profile";

    public static final String PHARMACY_INVENTORY = "Inventory";
    public static final String PHARMACY_RESERVATIONS = "Reservations";
    public static final String PHARMACY_ALERTS = "Alerts";
    public static final String PHARMACY_PROFILE = "Pharmacy Profile";

    public static final String ADMIN_DASHBOARD = "Dashboard";
    public static final String ADMIN_VERIFICATION = "Pharmacy Verification";
    public static final String ADMIN_CATALOG = "Medicine Catalog";
    public static final String ADMIN_REPORTS = "Reports";
    public static final String ADMIN_USERS = "Users";
    public static final String ADMIN_AUDIT = "Audit Log";
    public static final String ADMIN_NOTIFICATIONS = "Notifications";

    public static final String LOGOUT = "Logout";

    private static final int ITEM_HEIGHT = 38;

    /** The notifications item of the sidebar shown last (it carries the unread badge). */
    private static WeakReference<JButton> badgeButton = new WeakReference<JButton>(null);
    private static String badgeBaseText;
    /** The admin's "Reports (n)" item of the sidebar shown last. */
    private static WeakReference<JButton> reportsButton = new WeakReference<JButton>(null);

    private SidebarUtil() {
    }

    /** Menu items in order; a null value means the frame is not built yet. */
    private static Map<String, Supplier<JFrame>> menuFor(Role role) {
        Map<String, Supplier<JFrame>> m = new LinkedHashMap<String, Supplier<JFrame>>();
        if (role == Role.PATIENT) {
            m.put(PATIENT_SEARCH, SearchFrame::new);
            m.put(PATIENT_RESERVATIONS, MyReservationsFrame::new);
            m.put(PATIENT_NOTIFICATIONS, NotificationFrame::new);
            m.put(PATIENT_PROFILE, ProfileFrame::new);
        } else if (role == Role.PHARMACY) {
            m.put(PHARMACY_INVENTORY, InventoryFrame::new);
            m.put(PHARMACY_RESERVATIONS, PharmacyReservationsFrame::new);
            m.put(PHARMACY_ALERTS, AlertsFrame::new);
            m.put(PHARMACY_PROFILE, PharmacyProfileFrame::new);
        } else if (role == Role.ADMIN) {
            m.put(ADMIN_DASHBOARD, AdminDashboardFrame::new);
            m.put(ADMIN_VERIFICATION, VerificationFrame::new);
            m.put(ADMIN_CATALOG, MedicineCatalogFrame::new);
            m.put(ADMIN_REPORTS, ReportsFrame::new);
            m.put(ADMIN_USERS, UsersFrame::new);
            m.put(ADMIN_AUDIT, AuditLogFrame::new);
            m.put(ADMIN_NOTIFICATIONS, NotificationFrame::new);
        }
        return m;
    }

    /** True for the item that shows the unread count ("Alerts (4)", "Notifications (2)"). */
    private static boolean isNotificationsItem(String item) {
        return PATIENT_NOTIFICATIONS.equals(item) || PHARMACY_ALERTS.equals(item) || ADMIN_NOTIFICATIONS.equals(item);
    }

    /** "Alerts (4)" or just "Alerts". */
    private static String withCount(String base, int unread) {
        return unread > 0 ? base + " (" + unread + ")" : base;
    }

    /**
     * Updates the unread count on the current sidebar. Call on the Swing thread
     * (NotificationWatcher uses SwingUtilities.invokeLater).
     */
    public static void updateBadge(int unread) {
        JButton b = badgeButton.get();
        if (b != null && badgeBaseText != null) {
            b.setText(withCount(badgeBaseText, unread));
        }
    }

    /** Shows the number of OPEN reports on the admin's "Reports (n)" item (call on the Swing thread). */
    public static void updateReportsCount(int open) {
        JButton b = reportsButton.get();
        if (b != null) {
            b.setText(withCount(ADMIN_REPORTS, open));
        }
    }

    private static void refreshReportsCount() {
        try {
            updateReportsCount(new ReportService().countOpen());
        } catch (SQLException e) {
            ErrorLog.log("Open report count failed", e);
        }
    }

    /** Re-reads the unread count of the logged-in user and shows it (e.g. after "Mark all read"). */
    public static void refreshBadge() {
        Integer me = Session.getUserId();
        if (me == null) {
            return;
        }
        try {
            updateBadge(new NotificationService().countUnread(me));
        } catch (SQLException e) {
            ErrorLog.log("Unread count failed", e);
        }
    }

    /**
     * Fills pnlSidebar with the logged-in role's menu. activeItem (one of the constants
     * above) is highlighted. Call from the frame constructor after initComponents().
     */
    public static void attach(final JFrame current, JPanel pnlSidebar, final String activeItem) {
        if (!menuFor(Session.getRole()).containsKey(activeItem)) {
            deny(current, activeItem); // e.g. a patient reaching an admin screen
        }
        pnlSidebar.removeAll();
        pnlSidebar.setLayout(new BoxLayout(pnlSidebar, BoxLayout.Y_AXIS));
        pnlSidebar.setOpaque(true);
        pnlSidebar.setBackground(UITheme.SIDEBAR);
        pnlSidebar.setBorder(BorderFactory.createEmptyBorder(18, 0, 14, 0));

        JLabel brand = new JLabel("MediX", UITheme.createLogoIcon(28), SwingConstants.LEFT);
        brand.setIconTextGap(8);
        brand.setFont(UITheme.FONT_TITLE.deriveFont(20f));
        brand.setForeground(UITheme.WHITE);
        brand.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        pnlSidebar.add(brand);
        pnlSidebar.add(Box.createVerticalStrut(22));

        Map<String, Supplier<JFrame>> menu = menuFor(Session.getRole());
        for (final Map.Entry<String, Supplier<JFrame>> item : menu.entrySet()) {
            JButton b = createItem(item.getKey(), item.getKey().equals(activeItem));
            b.addActionListener(e -> open(current, item.getKey(), activeItem, item.getValue()));
            if (isNotificationsItem(item.getKey())) {
                badgeButton = new WeakReference<JButton>(b);
                badgeBaseText = item.getKey();
                refreshBadge();
            }
            if (ADMIN_REPORTS.equals(item.getKey())) {
                reportsButton = new WeakReference<JButton>(b);
                refreshReportsCount();
            }
            pnlSidebar.add(b);
            pnlSidebar.add(Box.createVerticalStrut(2));
        }

        pnlSidebar.add(Box.createVerticalGlue());
        JButton logout = createItem(LOGOUT, false);
        logout.addActionListener(e -> logout(current));
        pnlSidebar.add(logout);

        pnlSidebar.revalidate();
        pnlSidebar.repaint();
    }

    /**
     * Role check for frames without a sidebar (pop-ups such as PharmacyDetailFrame or AddAdminFrame):
     * call in the constructor after initComponents(). When the logged-in role is not allowed the frame
     * is closed right after it opens, the user is told and taken to their home screen. Returns false then.
     */
    public static boolean guard(JFrame frame, Role... allowed) {
        Role role = Session.getRole();
        for (Role r : allowed) {
            if (r == role) {
                return true;
            }
        }
        deny(frame, frame.getTitle().replace("MediX – ", ""));
        return false;
    }

    /** Closes the frame once it is shown, says why, logs ACCESS_DENIED and opens the user's home. */
    private static void deny(final JFrame frame, final String screen) {
        Role role = Session.getRole();
        new AuditService().log("ACCESS_DENIED", null, null,
                (role == null ? "Not logged in" : role.name()) + " tried to open " + screen);
        SwingUtilities.invokeLater(() -> {
            frame.dispose();
            JOptionPane.showMessageDialog(null, "You do not have access to " + screen + ".",
                    "MediX – No access", JOptionPane.WARNING_MESSAGE);
            JFrame home = role == null ? new LoginFrame() : homeFor(role);
            home.setVisible(true);
        });
    }

    /** The first screen of a role after login. */
    public static JFrame homeFor(Role role) {
        switch (role) {
            case ADMIN:
                return new AdminDashboardFrame();
            case PHARMACY:
                return new InventoryFrame();
            default:
                return new SearchFrame();
        }
    }

    /** Text for a frame's role label, e.g. "Pharmacy · Lazz Pharma". */
    public static String roleText() {
        Role role = Session.getRole();
        if (role == null) {
            return "Not logged in";
        }
        switch (role) {
            case ADMIN:
                return "Administrator";
            case PHARMACY:
                Pharmacy p = Session.getPharmacy();
                return p == null ? "Pharmacy" : "Pharmacy · " + p.getName();
            default:
                return "Patient";
        }
    }

    private static void open(JFrame current, String item, String activeItem, Supplier<JFrame> factory) {
        if (item.equals(activeItem)) {
            return;
        }
        if (factory == null) {
            JOptionPane.showMessageDialog(current, item + " is coming in a later part.",
                    "MediX – " + item, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        factory.get().setVisible(true);
        current.dispose();
    }

    private static void logout(JFrame current) {
        int answer = JOptionPane.showConfirmDialog(current, "Log out of MediX?", "MediX – Logout",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        NotificationWatcher.stop();
        new AuthService().logout();
        new LoginFrame().setVisible(true);
        current.dispose();
    }

    private static JButton createItem(String text, boolean active) {
        final JButton b = new JButton(text);
        final java.awt.Color normal = active ? UITheme.ACCENT : UITheme.SIDEBAR;
        b.setFont(active ? UITheme.FONT_BODY_BOLD : UITheme.FONT_BODY);
        b.setForeground(UITheme.WHITE);
        b.setBackground(normal);
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 8));
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.putClientProperty("JButton.buttonType", "square");
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, ITEM_HEIGHT));
        b.setPreferredSize(new Dimension(170, ITEM_HEIGHT));
        b.setToolTipText(text);
        if (!active) {
            b.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    b.setBackground(UITheme.SIDEBAR_HOVER);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    b.setBackground(normal);
                }
            });
        }
        return b;
    }
}
