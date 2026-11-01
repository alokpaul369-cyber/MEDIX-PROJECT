package medix.forms;

import java.awt.Color;
import java.awt.Component;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import medix.model.Role;
import medix.model.User;
import medix.model.UserDetails;
import medix.service.ServiceException;
import medix.service.UserService;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.FormUtil;
import medix.util.Session;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;

/**
 * Admin: every account. Filter by role, search by name or email, disable / enable an account
 * (an admin cannot disable themself) and add another admin.
 */
public class UsersFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Users";
    private static final int COL_ACTIVE = 6;
    private static final Color ROW_DISABLED = Color.decode("#EEF0F3");

    private final UserService userService = new UserService();
    private DefaultTableModel tableModel;
    /** Rows in table-model order. */
    private List<UserDetails> rows = new ArrayList<UserDetails>();

    public UsersFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.ADMIN_USERS);
        setupTable();
        cmbRole.setModel(new DefaultComboBoxModel<String>(new String[]{"All roles", Role.PATIENT.toString(),
            Role.PHARMACY.toString(), Role.ADMIN.toString()}));
        FormUtil.onTextChange(txtSearch, this::reload);
        reload();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.styleMuted(lblSelected);
        UITheme.stylePrimaryButton(btnAddAdmin);
        UITheme.styleDangerButton(btnDisable);
        UITheme.styleSuccessButton(btnEnable);
        txtSearch.putClientProperty("JTextField.placeholderText", "Name or email");
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblUsers, "ID", "Name", "Email", "Phone", "Role", "Area", "Active", "Joined");
        TableUtil.setColumnWidth(tblUsers, 1, 150);
        TableUtil.setColumnWidth(tblUsers, 2, 190);
        TableUtil.setColumnWidth(tblUsers, 3, 100);
        TableUtil.setColumnWidth(tblUsers, 4, 70);
        TableUtil.setColumnWidth(tblUsers, 5, 110);
        TableUtil.setColumnWidth(tblUsers, COL_ACTIVE, 60);
        TableUtil.setColumnWidth(tblUsers, 7, 90);
        tblUsers.getColumnModel().getColumn(COL_ACTIVE).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setHorizontalAlignment(CENTER);
                setFont(UITheme.FONT_BODY_BOLD);
                if (!selected) {
                    setForeground("Yes".equals(value) ? UITheme.SUCCESS : UITheme.DANGER);
                }
                return this;
            }
        });
        TableUtil.setRowBackground(tblUsers, modelRow -> rows.get(modelRow).getUser().isActive() ? null : ROW_DISABLED);
        tblUsers.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtons();
            }
        });
    }

    /** null = all roles */
    private Role selectedRole() {
        int i = cmbRole.getSelectedIndex();
        return i <= 0 ? null : Role.values()[i - 1];
    }

    private void reload() {
        Integer keep = TableUtil.getSelectedId(tblUsers);
        try {
            rows = userService.getUsers(selectedRole(), txtSearch.getText());
        } catch (ServiceException e) {
            rows = new ArrayList<UserDetails>();
            showWarning(e.getMessage());
        } catch (SQLException e) {
            rows = new ArrayList<UserDetails>();
            showError("Could not load the users:\n" + ErrorLog.describe(e));
        }
        TableUtil.clear(tableModel);
        for (UserDetails d : rows) {
            User u = d.getUser();
            tableModel.addRow(new Object[]{u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole(),
                d.getAreaName(), u.isActive() ? "Yes" : "No", DateUtil.toDisplayDate(u.getCreatedAt())});
        }
        if (keep == null || !TableUtil.selectId(tblUsers, keep)) {
            tblUsers.clearSelection();
        }
        updateButtons();
    }

    private UserDetails selected() {
        Integer id = TableUtil.getSelectedId(tblUsers);
        if (id == null) {
            return null;
        }
        for (UserDetails d : rows) {
            if (d.getUser().getId() == id) {
                return d;
            }
        }
        return null;
    }

    private boolean isMe(User u) {
        return Session.getUserId() != null && Session.getUserId() == u.getId();
    }

    private void updateButtons() {
        UserDetails d = selected();
        if (d == null) {
            lblSelected.setText(rows.size() + (rows.size() == 1 ? " user" : " users") + " · select one to disable or enable it");
            btnDisable.setEnabled(false);
            btnEnable.setEnabled(false);
            return;
        }
        User u = d.getUser();
        lblSelected.setText(u.getFullName() + " · " + u.getRole() + " · " + (u.isActive() ? "active" : "disabled")
                + (isMe(u) ? " · this is you" : ""));
        btnDisable.setEnabled(u.isActive() && !isMe(u));
        btnDisable.setToolTipText(isMe(u) ? "You cannot disable your own account." : null);
        btnEnable.setEnabled(!u.isActive());
    }

    private void setActive(boolean active) {
        UserDetails d = selected();
        if (d == null) {
            return;
        }
        User u = d.getUser();
        String question = active
                ? "Enable " + u.getFullName() + "?\nThey can log in again."
                : "Disable " + u.getFullName() + " (" + u.getEmail() + ")?\nThey will not be able to log in."
                + (u.getRole() == Role.PHARMACY ? "\nTo hide the pharmacy from patients, suspend it under Pharmacy Verification." : "");
        if (JOptionPane.showConfirmDialog(this, question, TITLE, JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            userService.setActive(u.getId(), active);
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not change the account:\n" + ErrorLog.describe(e));
        }
        reload();
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
        lblRole = new javax.swing.JLabel();
        cmbRole = new javax.swing.JComboBox<>();
        lblSearch = new javax.swing.JLabel();
        txtSearch = new javax.swing.JTextField();
        btnAddAdmin = new javax.swing.JButton();
        scrUsers = new javax.swing.JScrollPane();
        tblUsers = new javax.swing.JTable();
        lblSelected = new javax.swing.JLabel();
        btnDisable = new javax.swing.JButton();
        btnEnable = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Users");
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

        lblTitle.setText("Users");

        lblSubtitle.setText("Every patient, pharmacy and admin account. A disabled account cannot log in.");

        lblRole.setText("Role");

        cmbRole.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbRoleActionPerformed(evt);
            }
        });

        lblSearch.setText("Search");

        btnAddAdmin.setText("Add admin…");
        btnAddAdmin.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnAddAdminActionPerformed(evt);
            }
        });

        scrUsers.setViewportView(tblUsers);

        lblSelected.setText(" ");

        btnDisable.setText("Disable");
        btnDisable.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnDisableActionPerformed(evt);
            }
        });

        btnEnable.setText("Enable");
        btnEnable.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnEnableActionPerformed(evt);
            }
        });

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
                        .addComponent(lblRole)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(cmbRole, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(16, 16, 16)
                        .addComponent(lblSearch)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(txtSearch, javax.swing.GroupLayout.PREFERRED_SIZE, 240, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(20, 20, Short.MAX_VALUE)
                        .addComponent(btnAddAdmin))
                    .addComponent(scrUsers, javax.swing.GroupLayout.DEFAULT_SIZE, 960, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblSelected, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGap(20, 20, 20)
                        .addComponent(btnDisable, javax.swing.GroupLayout.PREFERRED_SIZE, 110, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnEnable, javax.swing.GroupLayout.PREFERRED_SIZE, 110, javax.swing.GroupLayout.PREFERRED_SIZE)))
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
                .addGap(16, 16, 16)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblRole)
                    .addComponent(cmbRole, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(lblSearch)
                    .addComponent(txtSearch, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnAddAdmin))
                .addGap(10, 10, 10)
                .addComponent(scrUsers, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(12, 12, 12)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblSelected)
                    .addComponent(btnDisable)
                    .addComponent(btnEnable))
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void cmbRoleActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbRoleActionPerformed
        reload();
    }//GEN-LAST:event_cmbRoleActionPerformed

    private void btnAddAdminActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnAddAdminActionPerformed
        new AddAdminFrame(this::reload).setVisible(true);
    }//GEN-LAST:event_btnAddAdminActionPerformed

    private void btnDisableActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDisableActionPerformed
        setActive(false);
    }//GEN-LAST:event_btnDisableActionPerformed

    private void btnEnableActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnEnableActionPerformed
        setActive(true);
    }//GEN-LAST:event_btnEnableActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnAddAdmin;
    private javax.swing.JButton btnDisable;
    private javax.swing.JButton btnEnable;
    private javax.swing.JComboBox<String> cmbRole;
    private javax.swing.JLabel lblRole;
    private javax.swing.JLabel lblSearch;
    private javax.swing.JLabel lblSelected;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrUsers;
    private javax.swing.JTable tblUsers;
    private javax.swing.JTextField txtSearch;
    // End of variables declaration//GEN-END:variables
}
