package medix.forms;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JOptionPane;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import medix.model.Notification;
import medix.model.Role;
import medix.service.NotificationService;
import medix.util.ErrorLog;
import medix.util.NotificationNavigator;
import medix.util.NotificationTableUtil;
import medix.util.Session;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;

/**
 * Notifications of a patient or an admin (pharmacies use AlertsFrame). Unread rows are bold;
 * Open shows the related screen; the list refreshes every 30 seconds.
 */
public class NotificationFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Notifications";
    private static final int REFRESH_MILLIS = 30 * 1000;

    private final NotificationService notificationService = new NotificationService();
    private DefaultTableModel tableModel;
    /** Rows in table-model order. */
    private List<Notification> rows = new ArrayList<Notification>();
    private final Timer timer;

    public NotificationFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, Session.hasRole(Role.ADMIN)
                ? SidebarUtil.ADMIN_NOTIFICATIONS : SidebarUtil.PATIENT_NOTIFICATIONS);
        cmbFilterType.setModel(new DefaultComboBoxModel<String>(NotificationTableUtil.filterItems()));
        tableModel = NotificationTableUtil.setup(tblNotifications, modelRow -> rows.get(modelRow));
        tblNotifications.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        reload();
        timer = new Timer(REFRESH_MILLIS, e -> reload());
        timer.start();
    }

    @Override
    public void dispose() {
        if (timer != null) {
            timer.stop();
        }
        super.dispose();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.stylePrimaryButton(btnOpen);
    }

    private void reload() {
        Integer me = Session.getUserId();
        if (me == null) {
            return;
        }
        Integer keep = TableUtil.getSelectedId(tblNotifications);
        int unread;
        try {
            rows = notificationService.getNotifications(me, NotificationTableUtil.typeAt(cmbFilterType.getSelectedIndex()));
            unread = notificationService.countUnread(me);
        } catch (SQLException e) {
            if (timer != null) {
                timer.stop();
            }
            JOptionPane.showMessageDialog(this, "Could not load notifications:\n" + ErrorLog.describe(e), TITLE,
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        NotificationTableUtil.fill(tableModel, rows);
        lblSubtitle.setText(unread + " unread  ·  newest first  ·  refreshes every 30 seconds");
        SidebarUtil.updateBadge(unread);
        if (keep == null || !TableUtil.selectId(tblNotifications, keep)) {
            tblNotifications.clearSelection();
        }
        showSelected();
    }

    private Notification selected() {
        int viewRow = tblNotifications.getSelectedRow();
        return viewRow < 0 ? null : rows.get(tblNotifications.convertRowIndexToModel(viewRow));
    }

    private void showSelected() {
        Notification n = selected();
        btnMarkRead.setEnabled(n != null && !n.isRead());
        btnOpen.setEnabled(n != null && NotificationNavigator.canOpen(n));
        btnMarkAllRead.setEnabled(!rows.isEmpty());
        lblDetail.setText(n == null ? (rows.isEmpty() ? "No notifications." : "Select a notification. Double-click opens it.")
                : UITheme.wrapHtml("<b>" + n.getType() + "</b> · " + n.getMessage().replace("<", "&lt;"), 900));
    }

    private void markSelectedRead() {
        Notification n = selected();
        Integer me = Session.getUserId();
        if (n == null || me == null) {
            return;
        }
        try {
            notificationService.markRead(n.getId(), me);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, ErrorLog.describe(e), TITLE, JOptionPane.ERROR_MESSAGE);
        }
        reload();
    }

    private void markAllRead() {
        Integer me = Session.getUserId();
        if (me == null) {
            return;
        }
        try {
            notificationService.markAllRead(me);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, ErrorLog.describe(e), TITLE, JOptionPane.ERROR_MESSAGE);
        }
        reload();
    }

    private void openSelected() {
        Notification n = selected();
        Integer me = Session.getUserId();
        if (n == null || me == null) {
            return;
        }
        try {
            notificationService.markRead(n.getId(), me);
        } catch (SQLException e) {
            ErrorLog.log("Mark read failed", e);
        }
        NotificationNavigator.open(this, n);
        if (isDisplayable()) {
            reload();
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        pnlSidebar = new javax.swing.JPanel();
        lblTitle = new javax.swing.JLabel();
        lblSubtitle = new javax.swing.JLabel();
        lblFilterType = new javax.swing.JLabel();
        cmbFilterType = new javax.swing.JComboBox<>();
        btnOpen = new javax.swing.JButton();
        btnMarkRead = new javax.swing.JButton();
        btnMarkAllRead = new javax.swing.JButton();
        scrNotifications = new javax.swing.JScrollPane();
        tblNotifications = new javax.swing.JTable();
        lblDetail = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Notifications");
        setPreferredSize(new java.awt.Dimension(1200, 720));

        javax.swing.GroupLayout pnlSidebarLayout = new javax.swing.GroupLayout(pnlSidebar);
        pnlSidebar.setLayout(pnlSidebarLayout);
        pnlSidebarLayout.setHorizontalGroup(
            pnlSidebarLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 170, Short.MAX_VALUE)
        );
        pnlSidebarLayout.setVerticalGroup(
            pnlSidebarLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 720, Short.MAX_VALUE)
        );

        lblTitle.setText("Notifications");

        lblSubtitle.setText("0 unread");

        lblFilterType.setText("Type");

        cmbFilterType.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbFilterTypeActionPerformed(evt);
            }
        });

        btnOpen.setText("Open");
        btnOpen.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnOpenActionPerformed(evt);
            }
        });

        btnMarkRead.setText("Mark read");
        btnMarkRead.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnMarkReadActionPerformed(evt);
            }
        });

        btnMarkAllRead.setText("Mark all read");
        btnMarkAllRead.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnMarkAllReadActionPerformed(evt);
            }
        });

        tblNotifications.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                tblNotificationsMouseClicked(evt);
            }
        });

        scrNotifications.setViewportView(tblNotifications);

        lblDetail.setText(" ");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(pnlSidebar, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(24, 24, 24)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblTitle)
                    .addComponent(lblSubtitle)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblFilterType)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(cmbFilterType, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(btnOpen)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnMarkRead)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnMarkAllRead))
                    .addComponent(scrNotifications, javax.swing.GroupLayout.DEFAULT_SIZE, 960, Short.MAX_VALUE)
                    .addComponent(lblDetail, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(24, 24, 24))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(pnlSidebar, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addGroup(layout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addComponent(lblTitle)
                .addGap(4, 4, 4)
                .addComponent(lblSubtitle)
                .addGap(14, 14, 14)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblFilterType)
                    .addComponent(cmbFilterType, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnOpen)
                    .addComponent(btnMarkRead)
                    .addComponent(btnMarkAllRead))
                .addGap(10, 10, 10)
                .addComponent(scrNotifications, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(8, 8, 8)
                .addComponent(lblDetail)
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void cmbFilterTypeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbFilterTypeActionPerformed
        if (tableModel != null) {
            reload();
        }
    }//GEN-LAST:event_cmbFilterTypeActionPerformed

    private void btnOpenActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnOpenActionPerformed
        openSelected();
    }//GEN-LAST:event_btnOpenActionPerformed

    private void btnMarkReadActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnMarkReadActionPerformed
        markSelectedRead();
    }//GEN-LAST:event_btnMarkReadActionPerformed

    private void btnMarkAllReadActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnMarkAllReadActionPerformed
        markAllRead();
    }//GEN-LAST:event_btnMarkAllReadActionPerformed

    private void tblNotificationsMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_tblNotificationsMouseClicked
        if (evt.getClickCount() == 2 && btnOpen.isEnabled()) {
            openSelected();
        }
    }//GEN-LAST:event_tblNotificationsMouseClicked

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnMarkAllRead;
    private javax.swing.JButton btnMarkRead;
    private javax.swing.JButton btnOpen;
    private javax.swing.JComboBox<String> cmbFilterType;
    private javax.swing.JLabel lblDetail;
    private javax.swing.JLabel lblFilterType;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrNotifications;
    private javax.swing.JTable tblNotifications;
    // End of variables declaration//GEN-END:variables
}
