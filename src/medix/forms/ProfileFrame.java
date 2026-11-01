package medix.forms;

import java.sql.SQLException;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import medix.model.Area;
import medix.model.User;
import medix.service.AreaService;
import medix.service.SearchService;
import medix.service.ServiceException;
import medix.service.UserService;
import medix.util.ErrorLog;
import medix.util.SidebarUtil;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * Patient profile: name, phone and home area (the default search location), change password,
 * and the privacy option "Delete my search history".
 */
public class ProfileFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Profile";
    /** Width of the wrapped privacy text (privacy card minus padding and the button). */
    private static final int PRIVACY_TEXT_WIDTH = 700;

    private final UserService userService = new UserService();
    private final SearchService searchService = new SearchService();
    private final AreaService areaService = new AreaService();

    public ProfileFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.PATIENT_PROFILE);
        loadAreas();
        loadProfile();
        showHistoryCount();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblWelcome);
        UITheme.styleMuted(lblRole);
        for (javax.swing.JPanel card : new javax.swing.JPanel[]{pnlDetails, pnlPassword, pnlPrivacy}) {
            UITheme.styleCard(card);
        }
        UITheme.styleHeading(lblDetailsTitle);
        UITheme.styleHeading(lblPasswordTitle);
        UITheme.styleHeading(lblPrivacyTitle);
        for (JLabel l : new JLabel[]{lblName, lblEmail, lblPhone, lblArea, lblCurrent, lblNew, lblConfirm}) {
            l.setFont(UITheme.FONT_BODY_BOLD);
        }
        UITheme.styleMuted(lblAreaHint);
        UITheme.styleMuted(lblPasswordHint);
        UITheme.styleMuted(lblPrivacyText);
        lblPrivacyText.setText(UITheme.wrapHtml("MediX keeps what you searched (the medicine and your area, never your"
                + " exact location) for " + SearchService.SEARCH_LOG_DAYS + " days to show which medicines are short."
                + " You can delete your search history at any time; it does not affect your reservations.",
                PRIVACY_TEXT_WIDTH));
        lblHistoryCount.setFont(UITheme.FONT_BODY_BOLD);
        txtEmail.setEditable(false);
        txtPhone.putClientProperty("JTextField.placeholderText", "01712345678");
        txtNewPassword.putClientProperty("JTextField.placeholderText", "At least " + ValidationUtil.MIN_PASSWORD_LENGTH
                + " characters");
        UITheme.stylePrimaryButton(btnSave);
        UITheme.stylePrimaryButton(btnChangePassword);
        UITheme.styleDangerButton(btnDeleteHistory);
    }

    private void loadAreas() {
        DefaultComboBoxModel<Area> areas = new DefaultComboBoxModel<Area>();
        try {
            for (Area a : areaService.getAreas()) {
                areas.addElement(a);
            }
        } catch (SQLException e) {
            showError("Could not load the areas:\n" + ErrorLog.describe(e));
        }
        cmbArea.setModel(areas);
    }

    private void loadProfile() {
        try {
            User me = userService.getMe();
            lblWelcome.setText("Welcome, " + me.getFullName());
            lblRole.setText(SidebarUtil.roleText());
            txtName.setText(me.getFullName());
            txtEmail.setText(me.getEmail());
            txtPhone.setText(me.getPhone());
            cmbArea.setSelectedIndex(-1);
            for (int i = 0; i < cmbArea.getItemCount(); i++) {
                if (me.getAreaId() != null && cmbArea.getItemAt(i).getId() == me.getAreaId()) {
                    cmbArea.setSelectedIndex(i);
                }
            }
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not load your profile:\n" + ErrorLog.describe(e));
        }
    }

    private void showHistoryCount() {
        int n;
        try {
            n = searchService.countMySearches();
        } catch (SQLException e) {
            lblHistoryCount.setText(" ");
            btnDeleteHistory.setEnabled(false);
            return;
        }
        lblHistoryCount.setText(n == 0 ? "No saved searches." : "You have " + n + (n == 1 ? " saved search." : " saved searches."));
        btnDeleteHistory.setEnabled(n > 0);
    }

    // ---------------------------------------------------------------- actions

    private void saveProfile() {
        if (ValidationUtil.isBlank(txtName.getText())) {
            fail("Enter your name.", txtName);
            return;
        }
        if (!ValidationUtil.isValidBdMobile(txtPhone.getText())) {
            fail("Enter a Bangladeshi mobile number like 01712345678.", txtPhone);
            return;
        }
        Area area = (Area) cmbArea.getSelectedItem();
        if (area == null) {
            fail("Choose your area.", cmbArea);
            return;
        }
        try {
            userService.updateMyProfile(txtName.getText(), txtPhone.getText(), area.getId());
            loadProfile();
            showInfo("Your profile was saved.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not save your profile:\n" + ErrorLog.describe(e));
        }
    }

    private void changePassword() {
        String current = new String(txtCurrentPassword.getPassword());
        String next = new String(txtNewPassword.getPassword());
        if (current.isEmpty()) {
            fail("Enter your current password.", txtCurrentPassword);
            return;
        }
        if (!ValidationUtil.isValidPassword(next)) {
            fail("The new password must be at least " + ValidationUtil.MIN_PASSWORD_LENGTH + " characters.",
                    txtNewPassword);
            return;
        }
        if (!next.equals(new String(txtConfirmPassword.getPassword()))) {
            fail("The new passwords do not match.", txtConfirmPassword);
            return;
        }
        try {
            userService.changeMyPassword(current, next, new String(txtConfirmPassword.getPassword()));
            txtCurrentPassword.setText("");
            txtNewPassword.setText("");
            txtConfirmPassword.setText("");
            showInfo("Your password was changed. Use the new one next time you log in.");
        } catch (ServiceException e) {
            fail(e.getMessage(), e.getMessage().startsWith("The current") ? txtCurrentPassword : txtNewPassword);
        } catch (SQLException e) {
            showError("Could not change the password:\n" + ErrorLog.describe(e));
        }
    }

    private void deleteHistory() {
        if (JOptionPane.showConfirmDialog(this, "Delete all your saved searches?\nThis cannot be undone.", TITLE,
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            int n = searchService.deleteMySearchHistory();
            showInfo(n + (n == 1 ? " search was" : " searches were") + " deleted.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not delete your search history:\n" + ErrorLog.describe(e));
        }
        showHistoryCount();
    }

    // ---------------------------------------------------------------- helpers

    private void fail(String message, JComponent field) {
        showWarning(message);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
    }

    private void showInfo(String message) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.INFORMATION_MESSAGE);
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
        lblWelcome = new javax.swing.JLabel();
        lblRole = new javax.swing.JLabel();
        pnlDetails = new javax.swing.JPanel();
        lblDetailsTitle = new javax.swing.JLabel();
        lblName = new javax.swing.JLabel();
        txtName = new javax.swing.JTextField();
        lblEmail = new javax.swing.JLabel();
        txtEmail = new javax.swing.JTextField();
        lblPhone = new javax.swing.JLabel();
        txtPhone = new javax.swing.JTextField();
        lblArea = new javax.swing.JLabel();
        cmbArea = new javax.swing.JComboBox<>();
        lblAreaHint = new javax.swing.JLabel();
        btnSave = new javax.swing.JButton();
        pnlPassword = new javax.swing.JPanel();
        lblPasswordTitle = new javax.swing.JLabel();
        lblCurrent = new javax.swing.JLabel();
        txtCurrentPassword = new javax.swing.JPasswordField();
        lblNew = new javax.swing.JLabel();
        txtNewPassword = new javax.swing.JPasswordField();
        lblConfirm = new javax.swing.JLabel();
        txtConfirmPassword = new javax.swing.JPasswordField();
        lblPasswordHint = new javax.swing.JLabel();
        btnChangePassword = new javax.swing.JButton();
        pnlPrivacy = new javax.swing.JPanel();
        lblPrivacyTitle = new javax.swing.JLabel();
        lblPrivacyText = new javax.swing.JLabel();
        lblHistoryCount = new javax.swing.JLabel();
        btnDeleteHistory = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Profile");
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

        lblWelcome.setText("Welcome");

        lblRole.setText("Patient");

        lblDetailsTitle.setText("My details");

        lblName.setText("Full name");

        lblEmail.setText("Email (cannot be changed)");

        lblPhone.setText("Mobile number");

        lblArea.setText("My area");

        lblAreaHint.setText("Medicine search starts from this area.");

        btnSave.setText("Save details");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlDetailsLayout = new javax.swing.GroupLayout(pnlDetails);
        pnlDetails.setLayout(pnlDetailsLayout);
        pnlDetailsLayout.setHorizontalGroup(
            pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlDetailsLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblDetailsTitle, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblName)
                    .addComponent(txtName, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblEmail)
                    .addComponent(txtEmail, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblPhone)
                    .addComponent(txtPhone, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblArea)
                    .addComponent(cmbArea, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblAreaHint, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnSave, javax.swing.GroupLayout.Alignment.TRAILING))
                .addGap(16, 16, 16))
        );
        pnlDetailsLayout.setVerticalGroup(
            pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlDetailsLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(lblDetailsTitle)
                .addGap(8, 8, 8)
                .addGap(8, 8, 8)
                .addComponent(lblName)
                .addGap(4, 4, 4)
                .addComponent(txtName, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblEmail)
                .addGap(4, 4, 4)
                .addComponent(txtEmail, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblPhone)
                .addGap(4, 4, 4)
                .addComponent(txtPhone, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblArea)
                .addGap(4, 4, 4)
                .addComponent(cmbArea, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(4, 4, 4)
                .addComponent(lblAreaHint)
                .addGap(16, 16, Short.MAX_VALUE)
                .addComponent(btnSave)
                .addGap(16, 16, 16))
        );

        lblPasswordTitle.setText("Change password");

        lblCurrent.setText("Current password");

        lblNew.setText("New password");

        lblConfirm.setText("Repeat the new password");

        lblPasswordHint.setText("You stay logged in after the change.");

        btnChangePassword.setText("Change password");
        btnChangePassword.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnChangePasswordActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlPasswordLayout = new javax.swing.GroupLayout(pnlPassword);
        pnlPassword.setLayout(pnlPasswordLayout);
        pnlPasswordLayout.setHorizontalGroup(
            pnlPasswordLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlPasswordLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlPasswordLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblPasswordTitle, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblCurrent)
                    .addComponent(txtCurrentPassword, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblNew)
                    .addComponent(txtNewPassword, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblConfirm)
                    .addComponent(txtConfirmPassword, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblPasswordHint, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnChangePassword, javax.swing.GroupLayout.Alignment.TRAILING))
                .addGap(16, 16, 16))
        );
        pnlPasswordLayout.setVerticalGroup(
            pnlPasswordLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlPasswordLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(lblPasswordTitle)
                .addGap(8, 8, 8)
                .addGap(8, 8, 8)
                .addComponent(lblCurrent)
                .addGap(4, 4, 4)
                .addComponent(txtCurrentPassword, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblNew)
                .addGap(4, 4, 4)
                .addComponent(txtNewPassword, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblConfirm)
                .addGap(4, 4, 4)
                .addComponent(txtConfirmPassword, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(lblPasswordHint)
                .addGap(16, 16, Short.MAX_VALUE)
                .addComponent(btnChangePassword)
                .addGap(16, 16, 16))
        );

        lblPrivacyTitle.setText("Privacy");

        lblPrivacyText.setText("Text");

        lblHistoryCount.setText(" ");

        btnDeleteHistory.setText("Delete my search history");
        btnDeleteHistory.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnDeleteHistoryActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlPrivacyLayout = new javax.swing.GroupLayout(pnlPrivacy);
        pnlPrivacy.setLayout(pnlPrivacyLayout);
        pnlPrivacyLayout.setHorizontalGroup(
            pnlPrivacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlPrivacyLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlPrivacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblPrivacyTitle, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblPrivacyText, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(pnlPrivacyLayout.createSequentialGroup()
                        .addComponent(lblHistoryCount, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGap(20, 20, 20)
                        .addComponent(btnDeleteHistory)))
                .addGap(16, 16, 16))
        );
        pnlPrivacyLayout.setVerticalGroup(
            pnlPrivacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlPrivacyLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(lblPrivacyTitle)
                .addGap(6, 6, 6)
                .addComponent(lblPrivacyText)
                .addGap(10, 10, 10)
                .addGroup(pnlPrivacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblHistoryCount)
                    .addComponent(btnDeleteHistory))
                .addGap(16, 16, 16))
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(pnlSidebar, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(24, 24, 24)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblWelcome)
                    .addComponent(lblRole)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(pnlDetails, javax.swing.GroupLayout.DEFAULT_SIZE, 470, Short.MAX_VALUE)
                        .addGap(20, 20, 20)
                        .addComponent(pnlPassword, javax.swing.GroupLayout.DEFAULT_SIZE, 470, Short.MAX_VALUE))
                    .addComponent(pnlPrivacy, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(24, 24, 24))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(pnlSidebar, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addGroup(layout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addComponent(lblWelcome)
                .addGap(4, 4, 4)
                .addComponent(lblRole)
                .addGap(16, 16, 16)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(pnlDetails, javax.swing.GroupLayout.PREFERRED_SIZE, 345, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(pnlPassword, javax.swing.GroupLayout.PREFERRED_SIZE, 345, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(20, 20, 20)
                .addComponent(pnlPrivacy, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(20, 20, Short.MAX_VALUE))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
        saveProfile();
    }//GEN-LAST:event_btnSaveActionPerformed

    private void btnChangePasswordActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnChangePasswordActionPerformed
        changePassword();
    }//GEN-LAST:event_btnChangePasswordActionPerformed

    private void btnDeleteHistoryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDeleteHistoryActionPerformed
        deleteHistory();
    }//GEN-LAST:event_btnDeleteHistoryActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnChangePassword;
    private javax.swing.JButton btnDeleteHistory;
    private javax.swing.JButton btnSave;
    private javax.swing.JComboBox<medix.model.Area> cmbArea;
    private javax.swing.JLabel lblArea;
    private javax.swing.JLabel lblAreaHint;
    private javax.swing.JLabel lblConfirm;
    private javax.swing.JLabel lblCurrent;
    private javax.swing.JLabel lblDetailsTitle;
    private javax.swing.JLabel lblEmail;
    private javax.swing.JLabel lblHistoryCount;
    private javax.swing.JLabel lblName;
    private javax.swing.JLabel lblNew;
    private javax.swing.JLabel lblPasswordHint;
    private javax.swing.JLabel lblPasswordTitle;
    private javax.swing.JLabel lblPhone;
    private javax.swing.JLabel lblPrivacyText;
    private javax.swing.JLabel lblPrivacyTitle;
    private javax.swing.JLabel lblRole;
    private javax.swing.JLabel lblWelcome;
    private javax.swing.JPanel pnlDetails;
    private javax.swing.JPanel pnlPassword;
    private javax.swing.JPanel pnlPrivacy;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JPasswordField txtConfirmPassword;
    private javax.swing.JPasswordField txtCurrentPassword;
    private javax.swing.JTextField txtEmail;
    private javax.swing.JTextField txtName;
    private javax.swing.JPasswordField txtNewPassword;
    private javax.swing.JTextField txtPhone;
    // End of variables declaration//GEN-END:variables
}
