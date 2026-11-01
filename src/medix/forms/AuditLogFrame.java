package medix.forms;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import medix.model.AuditLog;
import medix.model.AuditLogView;
import medix.model.NamedCount;
import medix.service.AuditService;
import medix.service.ServiceException;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;

/**
 * Admin: the audit log (read-only), newest first. Filters: user, action and a date range
 * (two JDateChoosers, whole days). "Export CSV" saves the rows shown.
 */
public class AuditLogFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Audit Log";
    private static final int COL_DETAILS = 6;
    private static final String ALL_USERS = "All users";
    private static final String SYSTEM = "System (no user)";
    private static final String ALL_ACTIONS = "All actions";

    private final AuditService auditService = new AuditService();
    private DefaultTableModel tableModel;
    private List<AuditLogView> rows = new ArrayList<AuditLogView>();
    /** Users in cmbUser after the two fixed items. */
    private List<NamedCount> users = new ArrayList<NamedCount>();
    private boolean updatingFilters;

    public AuditLogFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.ADMIN_AUDIT);
        setupTable();
        fillFilters();
        dtcFrom.addPropertyChangeListener("date", e -> filtersChanged());
        dtcTo.addPropertyChangeListener("date", e -> filtersChanged());
        reload();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.styleMuted(lblCount);
        UITheme.stylePrimaryButton(btnExportCsv);
        UITheme.styleLinkButton(btnClear);
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblAudit, "ID", "Time", "User", "Action", "Entity", "Entity ID", "Details");
        TableUtil.setColumnWidth(tblAudit, 1, 165);
        TableUtil.setColumnWidth(tblAudit, 2, 120);
        TableUtil.setColumnWidth(tblAudit, 3, 180);
        TableUtil.setColumnWidth(tblAudit, 4, 85);
        TableUtil.setColumnWidth(tblAudit, 5, 65);
        TableUtil.setColumnWidth(tblAudit, COL_DETAILS, 300);
        TableUtil.centerColumn(tblAudit, 5);
        tblAudit.setToolTipText("Double-click an entry to read all of it.");
    }

    /** User and action lists from the log itself; keeps the current choice. */
    private void fillFilters() {
        Object user = cmbUser.getSelectedItem();
        Object action = cmbAction.getSelectedItem();
        List<String> actions = new ArrayList<String>();
        try {
            users = auditService.getLoggedUsers();
            actions = auditService.getActions();
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not load the filters:\n" + ErrorLog.describe(e));
        }
        updatingFilters = true;
        DefaultComboBoxModel<String> userModel = new DefaultComboBoxModel<String>();
        userModel.addElement(ALL_USERS);
        userModel.addElement(SYSTEM);
        for (NamedCount u : users) {
            userModel.addElement(u.getLabel());
        }
        cmbUser.setModel(userModel);
        DefaultComboBoxModel<String> actionModel = new DefaultComboBoxModel<String>();
        actionModel.addElement(ALL_ACTIONS);
        for (String a : actions) {
            actionModel.addElement(a);
        }
        cmbAction.setModel(actionModel);
        if (user != null) {
            cmbUser.setSelectedItem(user);
        }
        if (action != null) {
            cmbAction.setSelectedItem(action);
        }
        updatingFilters = false;
    }

    /** null = everyone, AuditService.SYSTEM_USER = entries without a user */
    private Integer selectedUser() {
        int i = cmbUser.getSelectedIndex();
        if (i <= 0) {
            return null;
        }
        return i == 1 ? AuditService.SYSTEM_USER : users.get(i - 2).getId();
    }

    private String selectedAction() {
        int i = cmbAction.getSelectedIndex();
        return i <= 0 ? null : (String) cmbAction.getSelectedItem();
    }

    private void filtersChanged() {
        if (!updatingFilters) {
            reload();
        }
    }

    private void reload() {
        Date from = dtcFrom.getDate();
        Date to = dtcTo.getDate();
        TableUtil.clear(tableModel);
        rows = new ArrayList<AuditLogView>();
        if (from != null && to != null && DateUtil.formatDate(from).compareTo(DateUtil.formatDate(to)) > 0) {
            UITheme.styleError(lblCount);
            lblCount.setText("The From date is after the To date.");
            btnExportCsv.setEnabled(false);
            return;
        }
        int total = 0;
        try {
            rows = auditService.search(selectedUser(), selectedAction(), from, to);
            total = rows.size() < AuditService.MAX_ROWS ? rows.size()
                    : auditService.count(selectedUser(), selectedAction(), from, to);
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not load the audit log:\n" + ErrorLog.describe(e));
        }
        for (AuditLogView v : rows) {
            AuditLog a = v.getLog();
            tableModel.addRow(new Object[]{a.getId(), DateUtil.toDisplay(a.getCreatedAt()), v.getUserName(),
                a.getAction(), a.getEntity() == null ? "" : a.getEntity(),
                a.getEntityId() == null ? "" : String.valueOf(a.getEntityId()), a.getDetails() == null ? "" : a.getDetails()});
        }
        UITheme.styleMuted(lblCount);
        lblCount.setText(rows.size() == total ? total + (total == 1 ? " entry" : " entries")
                : "Showing the newest " + rows.size() + " of " + total + " entries. Narrow the filters to see older ones.");
        btnExportCsv.setEnabled(!rows.isEmpty());
    }

    private void clearFilters() {
        updatingFilters = true;
        cmbUser.setSelectedIndex(0);
        cmbAction.setSelectedIndex(0);
        dtcFrom.setDate(null);
        dtcTo.setDate(null);
        updatingFilters = false;
        fillFilters();
        reload();
    }

    private void showEntry() {
        int viewRow = tblAudit.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        AuditLog a = rows.get(tblAudit.convertRowIndexToModel(viewRow)).getLog();
        String user = rows.get(tblAudit.convertRowIndexToModel(viewRow)).getUserName();
        JOptionPane.showMessageDialog(this, "Entry #" + a.getId() + "\n"
                + "Time: " + DateUtil.toDisplay(a.getCreatedAt()) + "\n"
                + "User: " + user + "\n"
                + "Action: " + a.getAction() + "\n"
                + "Entity: " + (a.getEntity() == null ? "-" : a.getEntity()
                        + (a.getEntityId() == null ? "" : " #" + a.getEntityId())) + "\n\n"
                + (a.getDetails() == null ? "(no details)" : a.getDetails()), TITLE, JOptionPane.INFORMATION_MESSAGE);
    }

    private void exportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save the audit log as CSV");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV file (*.csv)", "csv"));
        chooser.setSelectedFile(new File("medix_audit_log_" + DateUtil.today() + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".csv")) {
            file = new File(file.getParentFile(), file.getName() + ".csv");
        }
        if (file.exists() && JOptionPane.showConfirmDialog(this, file.getName() + " already exists. Replace it?",
                TITLE, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            auditService.exportCsv(rows, file);
            JOptionPane.showMessageDialog(this, rows.size() + " entries saved to\n" + file.getAbsolutePath(), TITLE,
                    JOptionPane.INFORMATION_MESSAGE);
            fillFilters(); // AUDIT_LOG_EXPORTED is a new action
            reload();
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (IOException e) {
            showError("Could not save the file:\n" + ErrorLog.describe(e));
        }
    }

    private void showWarning(String message) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.ERROR_MESSAGE);
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
        lblUser = new javax.swing.JLabel();
        cmbUser = new javax.swing.JComboBox<>();
        lblAction = new javax.swing.JLabel();
        cmbAction = new javax.swing.JComboBox<>();
        lblFrom = new javax.swing.JLabel();
        dtcFrom = new com.toedter.calendar.JDateChooser();
        lblTo = new javax.swing.JLabel();
        dtcTo = new com.toedter.calendar.JDateChooser();
        btnClear = new javax.swing.JButton();
        btnRefresh = new javax.swing.JButton();
        btnExportCsv = new javax.swing.JButton();
        scrAudit = new javax.swing.JScrollPane();
        tblAudit = new javax.swing.JTable();
        lblCount = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Audit Log");
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

        lblTitle.setText("Audit Log");

        lblSubtitle.setText("Who did what and when. Read-only, newest first.");

        lblUser.setText("User");

        cmbUser.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbUserActionPerformed(evt);
            }
        });

        lblAction.setText("Action");

        cmbAction.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbActionActionPerformed(evt);
            }
        });

        lblFrom.setText("From");

        dtcFrom.setDateFormatString("yyyy-MM-dd");

        lblTo.setText("To");

        dtcTo.setDateFormatString("yyyy-MM-dd");

        btnClear.setText("Clear filters");
        btnClear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnClearActionPerformed(evt);
            }
        });

        btnRefresh.setText("Refresh");
        btnRefresh.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRefreshActionPerformed(evt);
            }
        });

        btnExportCsv.setText("Export CSV");
        btnExportCsv.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnExportCsvActionPerformed(evt);
            }
        });

        tblAudit.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                tblAuditMouseClicked(evt);
            }
        });

        scrAudit.setViewportView(tblAudit);

        lblCount.setText(" ");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(pnlSidebar, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(24, 24, 24)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblTitle)
                            .addComponent(lblSubtitle))
                        .addGap(20, 20, Short.MAX_VALUE)
                        .addComponent(btnRefresh)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnExportCsv))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblUser)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(cmbUser, javax.swing.GroupLayout.PREFERRED_SIZE, 200, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(14, 14, 14)
                        .addComponent(lblAction)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(cmbAction, javax.swing.GroupLayout.PREFERRED_SIZE, 180, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(14, 14, 14)
                        .addComponent(lblFrom)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(dtcFrom, javax.swing.GroupLayout.PREFERRED_SIZE, 120, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(10, 10, 10)
                        .addComponent(lblTo)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(dtcTo, javax.swing.GroupLayout.PREFERRED_SIZE, 120, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(6, 6, 6)
                        .addComponent(btnClear))
                    .addComponent(scrAudit, javax.swing.GroupLayout.DEFAULT_SIZE, 960, Short.MAX_VALUE)
                    .addComponent(lblCount, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(24, 24, 24))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(pnlSidebar, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addGroup(layout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblTitle)
                        .addGap(4, 4, 4)
                        .addComponent(lblSubtitle))
                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(btnRefresh)
                        .addComponent(btnExportCsv)))
                .addGap(16, 16, 16)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblUser)
                    .addComponent(cmbUser, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(lblAction)
                    .addComponent(cmbAction, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(lblFrom)
                    .addComponent(dtcFrom, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(lblTo)
                    .addComponent(dtcTo, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnClear))
                .addGap(10, 10, 10)
                .addComponent(scrAudit, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(8, 8, 8)
                .addComponent(lblCount)
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void cmbUserActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbUserActionPerformed
        filtersChanged();
    }//GEN-LAST:event_cmbUserActionPerformed

    private void cmbActionActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbActionActionPerformed
        filtersChanged();
    }//GEN-LAST:event_cmbActionActionPerformed

    private void btnClearActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearActionPerformed
        clearFilters();
    }//GEN-LAST:event_btnClearActionPerformed

    private void btnRefreshActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRefreshActionPerformed
        fillFilters();
        reload();
    }//GEN-LAST:event_btnRefreshActionPerformed

    private void btnExportCsvActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnExportCsvActionPerformed
        exportCsv();
    }//GEN-LAST:event_btnExportCsvActionPerformed

    private void tblAuditMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_tblAuditMouseClicked
        if (evt.getClickCount() == 2) {
            showEntry();
        }
    }//GEN-LAST:event_tblAuditMouseClicked

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnClear;
    private javax.swing.JButton btnExportCsv;
    private javax.swing.JButton btnRefresh;
    private javax.swing.JComboBox<String> cmbAction;
    private javax.swing.JComboBox<String> cmbUser;
    private com.toedter.calendar.JDateChooser dtcFrom;
    private com.toedter.calendar.JDateChooser dtcTo;
    private javax.swing.JLabel lblAction;
    private javax.swing.JLabel lblCount;
    private javax.swing.JLabel lblFrom;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblTo;
    private javax.swing.JLabel lblUser;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrAudit;
    private javax.swing.JTable tblAudit;
    // End of variables declaration//GEN-END:variables
}
