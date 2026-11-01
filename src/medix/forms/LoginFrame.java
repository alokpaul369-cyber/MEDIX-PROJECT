package medix.forms;

import java.awt.Cursor;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import medix.model.User;
import medix.service.AuthException;
import medix.service.AuthService;
import medix.service.ServiceException;
import medix.util.DemoDataSeeder;
import medix.util.ErrorLog;
import medix.util.NotificationWatcher;
import medix.util.SidebarUtil;
import medix.util.UITheme;

/**
 * First screen: email + password login. Opens the home frame for the user's role.
 * "Load demo data" fills an empty database with demo accounts, stock and history.
 */
public class LoginFrame extends javax.swing.JFrame {

    private static final String DEMO_TITLE = "MediX – Demo Data";

    private final AuthService authService = new AuthService();
    private char passwordEchoChar;

    public LoginFrame() {
        this(null);
    }

    /** Opens the login screen with the email already filled in (after registration). */
    public LoginFrame(String email) {
        initComponents();
        applyTheme();
        if (email != null) {
            txtEmail.setText(email);
            txtPassword.requestFocusInWindow();
        }
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        lblLogo.setText("");
        lblLogo.setIcon(UITheme.createLogoIcon(64));
        lblTitle.setFont(UITheme.FONT_TITLE.deriveFont(28f));
        lblTitle.setForeground(UITheme.SIDEBAR);
        UITheme.styleMuted(lblSubtitle);
        lblEmail.setFont(UITheme.FONT_BODY_BOLD);
        lblPassword.setFont(UITheme.FONT_BODY_BOLD);
        txtEmail.putClientProperty("JTextField.placeholderText", "you@example.com");
        chkShowPassword.setOpaque(false);
        UITheme.styleError(lblError);
        lblError.setVisible(false);
        UITheme.stylePrimaryButton(btnLogin);
        UITheme.styleLinkButton(btnCreateAccount);
        UITheme.styleLinkButton(btnDemoData);
        btnDemoData.setForeground(UITheme.TEXT_MUTED);
        btnDemoData.setFont(UITheme.FONT_BODY.deriveFont(12f));
        btnDemoData.setToolTipText("Adds demo patients, pharmacies, stock and history (only once).");
        passwordEchoChar = txtPassword.getEchoChar();
        getRootPane().setDefaultButton(btnLogin);
    }

    private void login() {
        lblError.setVisible(false);
        String email = txtEmail.getText().trim();
        String password = new String(txtPassword.getPassword());
        if (email.isEmpty() || password.isEmpty()) {
            showError("Enter your email and password.");
            (email.isEmpty() ? txtEmail : txtPassword).requestFocusInWindow();
            return;
        }
        try {
            User user = authService.login(email, password);
            openHome(user);
        } catch (AuthException e) {
            showError(e.getMessage());
            txtPassword.setText("");
            txtPassword.requestFocusInWindow();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not reach the database:\n" + ErrorLog.describe(e),
                    "MediX – Login", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openHome(User user) {
        SidebarUtil.homeFor(user.getRole()).setVisible(true);
        NotificationWatcher.start(); // toasts, tray messages and the sidebar badge from now on
        dispose();
    }

    private void showError(String message) {
        UITheme.styleError(lblError);
        lblError.setText("<html>" + message.replace("\n", "<br>") + "</html>");
        lblError.setVisible(true);
    }

    private void showStatus(String message) {
        UITheme.styleMuted(lblError);
        lblError.setText(message);
        lblError.setVisible(true);
    }

    // ---------------------------------------------------------------- demo data

    /** "Load demo data": asks first, does nothing when the demo accounts already exist. */
    private void loadDemoData() {
        try {
            if (DemoDataSeeder.isLoaded()) {
                JOptionPane.showMessageDialog(this, "The demo data is already loaded.\n\n" + demoLogins(),
                        DEMO_TITLE, JOptionPane.INFORMATION_MESSAGE);
                txtEmail.setText(DemoDataSeeder.PATIENT_EMAIL);
                return;
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, ErrorLog.describe(e), DEMO_TITLE, JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Add demo patients, 12 pharmacies with stock, 14 days of searches,\n"
                + "reservations and reports to this database?\n\nIt takes about half a minute.", DEMO_TITLE,
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        setBusy(true);
        new SwingWorker<Boolean, String>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return DemoDataSeeder.load(this::publish);
            }

            @Override
            protected void process(List<String> steps) {
                showStatus(steps.get(steps.size() - 1));
            }

            @Override
            protected void done() {
                setBusy(false);
                lblError.setVisible(false);
                try {
                    get();
                    txtEmail.setText(DemoDataSeeder.PATIENT_EMAIL);
                    txtPassword.setText("");
                    txtPassword.requestFocusInWindow();
                    JOptionPane.showMessageDialog(LoginFrame.this, "Demo data loaded.\n\n" + demoLogins(), DEMO_TITLE,
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException | ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String message = cause instanceof ServiceException || cause instanceof AuthException
                            ? cause.getMessage() : ErrorLog.describe(cause);
                    JOptionPane.showMessageDialog(LoginFrame.this, "The demo data could not be loaded.\n" + message,
                            DEMO_TITLE, JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static String demoLogins() {
        return "Admin: admin@medix.com / admin123\n"
                + "Patient: " + DemoDataSeeder.PATIENT_EMAIL + " / " + DemoDataSeeder.PASSWORD + "\n"
                + "Pharmacy: pharmacy1@medix.com … pharmacy12@medix.com / " + DemoDataSeeder.PASSWORD;
    }

    private void setBusy(boolean busy) {
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        btnLogin.setEnabled(!busy);
        btnCreateAccount.setEnabled(!busy);
        btnDemoData.setEnabled(!busy);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        lblLogo = new javax.swing.JLabel();
        lblTitle = new javax.swing.JLabel();
        lblSubtitle = new javax.swing.JLabel();
        lblEmail = new javax.swing.JLabel();
        txtEmail = new javax.swing.JTextField();
        lblPassword = new javax.swing.JLabel();
        txtPassword = new javax.swing.JPasswordField();
        chkShowPassword = new javax.swing.JCheckBox();
        lblError = new javax.swing.JLabel();
        btnLogin = new javax.swing.JButton();
        btnCreateAccount = new javax.swing.JButton();
        btnDemoData = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Login");
        setPreferredSize(new java.awt.Dimension(400, 560));
        setResizable(false);

        lblLogo.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblLogo.setText("+");

        lblTitle.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblTitle.setText("MediX");

        lblSubtitle.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblSubtitle.setText("Find your medicine before you travel.");

        lblEmail.setText("Email");

        lblPassword.setText("Password");

        chkShowPassword.setText("Show password");
        chkShowPassword.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                chkShowPasswordActionPerformed(evt);
            }
        });

        lblError.setText("Error message");

        btnLogin.setText("Log In");
        btnLogin.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnLoginActionPerformed(evt);
            }
        });

        btnCreateAccount.setText("Don't have an account? Create one");
        btnCreateAccount.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCreateAccountActionPerformed(evt);
            }
        });

        btnDemoData.setText("Load demo data");
        btnDemoData.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnDemoDataActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(40, 40, 40)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblLogo, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblTitle, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblSubtitle, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblEmail)
                    .addComponent(txtEmail, javax.swing.GroupLayout.DEFAULT_SIZE, 320, Short.MAX_VALUE)
                    .addComponent(lblPassword)
                    .addComponent(txtPassword, javax.swing.GroupLayout.DEFAULT_SIZE, 320, Short.MAX_VALUE)
                    .addComponent(chkShowPassword)
                    .addComponent(lblError, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnLogin, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnCreateAccount, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnDemoData, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(40, 40, 40))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(28, 28, 28)
                .addComponent(lblLogo)
                .addGap(10, 10, 10)
                .addComponent(lblTitle)
                .addGap(2, 2, 2)
                .addComponent(lblSubtitle)
                .addGap(26, 26, 26)
                .addComponent(lblEmail)
                .addGap(4, 4, 4)
                .addComponent(txtEmail, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(12, 12, 12)
                .addComponent(lblPassword)
                .addGap(4, 4, 4)
                .addComponent(txtPassword, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(6, 6, 6)
                .addComponent(chkShowPassword)
                .addGap(8, 8, 8)
                .addComponent(lblError)
                .addGap(10, 10, 10)
                .addComponent(btnLogin, javax.swing.GroupLayout.PREFERRED_SIZE, 40, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(btnCreateAccount)
                .addGap(2, 2, 2)
                .addComponent(btnDemoData)
                .addGap(10, 10, Short.MAX_VALUE))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void chkShowPasswordActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_chkShowPasswordActionPerformed
        txtPassword.setEchoChar(chkShowPassword.isSelected() ? (char) 0 : passwordEchoChar);
    }//GEN-LAST:event_chkShowPasswordActionPerformed

    private void btnLoginActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnLoginActionPerformed
        login();
    }//GEN-LAST:event_btnLoginActionPerformed

    private void btnCreateAccountActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCreateAccountActionPerformed
        new RegisterFrame().setVisible(true);
        dispose();
    }//GEN-LAST:event_btnCreateAccountActionPerformed

    private void btnDemoDataActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDemoDataActionPerformed
        loadDemoData();
    }//GEN-LAST:event_btnDemoDataActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCreateAccount;
    private javax.swing.JButton btnDemoData;
    private javax.swing.JButton btnLogin;
    private javax.swing.JCheckBox chkShowPassword;
    private javax.swing.JLabel lblEmail;
    private javax.swing.JLabel lblError;
    private javax.swing.JLabel lblLogo;
    private javax.swing.JLabel lblPassword;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JTextField txtEmail;
    private javax.swing.JPasswordField txtPassword;
    // End of variables declaration//GEN-END:variables
}
