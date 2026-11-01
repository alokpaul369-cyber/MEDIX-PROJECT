package medix.forms;

import java.awt.Color;
import java.awt.Component;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import medix.engine.GeoUtil;
import medix.model.Pharmacy;
import medix.model.PharmacyDetails;
import medix.model.PharmacyStatus;
import medix.model.User;
import medix.service.ServiceException;
import medix.service.VerificationService;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.FormUtil;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * Admin: check pharmacy licenses and approve, reject, suspend or reactivate pharmacies.
 * Approve needs the license document opened and chkLicenseChecked ticked.
 * VERIFIED pharmacies checked more than 12 months ago show a yellow row and "Re-verify".
 */
public class VerificationFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Pharmacy Verification";
    private static final int COL_STATUS = 5;
    /** Width of the value column in the detail card (card 340 − padding − key column). */
    private static final int VALUE_WIDTH = 210;

    private final VerificationService verificationService = new VerificationService();
    private DefaultTableModel tableModel;
    /** Rows in table-model order. */
    private List<PharmacyDetails> rows = new ArrayList<PharmacyDetails>();
    private PharmacyDetails shown;
    /** Id of the pharmacy whose license was opened since it was selected. */
    private Integer licenseOpenedFor;
    private Integer lastShownId;
    private boolean updatingFilter;

    /** Opens the list with one pharmacy selected (switches the filter to All when needed). */
    public VerificationFrame(int pharmacyId) {
        this();
        if (!TableUtil.selectId(tblPharmacies, pharmacyId)) {
            cmbFilterStatus.setSelectedIndex(PharmacyStatus.values().length); // All
            TableUtil.selectId(tblPharmacies, pharmacyId);
        }
    }

    /**
     * The suspend flow from Reports: the pharmacy selected and the reason filled in, ready for
     * "Suspend" (the admin can still edit the reason or cancel).
     */
    public VerificationFrame(int pharmacyId, String suspendReason) {
        this(pharmacyId);
        if (shown != null && shown.getPharmacy().getId() == pharmacyId && btnSuspend.isEnabled()) {
            txtReason.setText(suspendReason);
            txtReason.setCaretPosition(0);
            SwingUtilities.invokeLater(btnSuspend::requestFocusInWindow);
        }
    }

    public VerificationFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.ADMIN_VERIFICATION);
        setupTable();
        updateFilterItems();
        FormUtil.onTextChange(txtSearch, this::reload);
        reload();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.styleCard(pnlDetails);
        UITheme.styleHeading(lblDetailName);
        lblReverify.setFont(UITheme.FONT_BODY_BOLD);
        lblReverify.setForeground(UITheme.ORANGE);
        for (JLabel key : new JLabel[]{lblOwnerKey, lblPhoneKey, lblEmailKey, lblLicenseKey, lblAddressKey,
            lblHoursKey, lblAppliedKey, lblLocationKey, lblVerifiedKey, lblNoteKey}) {
            UITheme.styleMuted(key);
        }
        lblReason.setFont(UITheme.FONT_BODY_BOLD);
        chkLicenseChecked.setOpaque(false);
        txtSearch.putClientProperty("JTextField.placeholderText", "Pharmacy name or license number");
        txtReason.putClientProperty("JTextField.placeholderText", "e.g. License number does not match the document");
        UITheme.styleSuccessButton(btnApprove);
        UITheme.styleDangerButton(btnReject);
        UITheme.styleWarningButton(btnSuspend);
        UITheme.stylePrimaryButton(btnReactivate);
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblPharmacies, "ID", "Pharmacy", "License no.", "Area", "Applied", "Status");
        TableUtil.setColumnWidth(tblPharmacies, 1, 140);
        TableUtil.setColumnWidth(tblPharmacies, 2, 100);
        TableUtil.setColumnWidth(tblPharmacies, 3, 90);
        TableUtil.setColumnWidth(tblPharmacies, 4, 88);
        TableUtil.setColumnWidth(tblPharmacies, COL_STATUS, 150);
        tblPharmacies.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                PharmacyDetails d = rows.get(table.convertRowIndexToModel(row));
                setText(statusHtml(d, selected));
                return this;
            }
        });
        TableUtil.setRowBackground(tblPharmacies,
                modelRow -> rows.get(modelRow).isReverificationDue() ? UITheme.NOTE_WARNING_BG : null);
        tblPharmacies.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
    }

    /** Coloured status text, plus an orange "Re-verify" when it is due. */
    private static String statusHtml(PharmacyDetails d, boolean selected) {
        PharmacyStatus status = d.getPharmacy().getStatus();
        String color = selected ? "#FFFFFF" : hex(UITheme.pharmacyStatusColor(status));
        String html = "<html><b><font color='" + color + "'>" + status + "</font></b>";
        if (d.isReverificationDue()) {
            html += "&nbsp;&nbsp;<b><font color='" + (selected ? "#FFFFFF" : hex(UITheme.ORANGE)) + "'>Re-verify</font></b>";
        }
        return html + "</html>";
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ---------------------------------------------------------------- loading

    /** Filter items: "Pending (n)", Verified, Rejected, Suspended, All. Keeps the selection. */
    private void updateFilterItems() {
        int selected = Math.max(0, cmbFilterStatus.getSelectedIndex());
        int pending;
        try {
            pending = verificationService.countByStatus(PharmacyStatus.PENDING);
        } catch (SQLException e) {
            pending = 0;
        }
        updatingFilter = true;
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>(new String[]{
            "Pending (" + pending + ")", "Verified", "Rejected", "Suspended", "All"});
        cmbFilterStatus.setModel(model);
        cmbFilterStatus.setSelectedIndex(selected);
        updatingFilter = false;
    }

    /** null = All */
    private PharmacyStatus selectedFilter() {
        int i = cmbFilterStatus.getSelectedIndex();
        return i >= 0 && i < PharmacyStatus.values().length ? PharmacyStatus.values()[i] : null;
    }

    private void reload() {
        Integer keep = shown == null ? null : shown.getPharmacy().getId();
        try {
            rows = verificationService.getPharmacies(selectedFilter(), txtSearch.getText());
        } catch (SQLException e) {
            rows = new ArrayList<PharmacyDetails>();
            showError("Could not load the pharmacies:\n" + ErrorLog.describe(e));
        }
        TableUtil.clear(tableModel);
        for (PharmacyDetails d : rows) {
            Pharmacy p = d.getPharmacy();
            tableModel.addRow(new Object[]{p.getId(), p.getName(), p.getLicenseNo(), d.getAreaName(),
                DateUtil.toDisplayDate(p.getCreatedAt()), p.getStatus()});
        }
        if (keep == null || !TableUtil.selectId(tblPharmacies, keep)) {
            tblPharmacies.clearSelection();
            showDetails(null);
        }
    }

    /** Re-reads counts, list and the selected pharmacy after a change. */
    private void refreshAll() {
        updateFilterItems();
        reload();
        showSelected();
    }

    // ---------------------------------------------------------------- details

    private void showSelected() {
        Integer id = TableUtil.getSelectedId(tblPharmacies);
        PharmacyDetails d = null;
        if (id != null) {
            try {
                d = verificationService.getDetails(id);
            } catch (SQLException e) {
                showError("Could not load the pharmacy:\n" + ErrorLog.describe(e));
            }
        }
        showDetails(d);
    }

    private void showDetails(PharmacyDetails d) {
        // a different pharmacy: its license must be opened and checked again
        // (a reload that briefly clears the selection does not count)
        if (d != null && (lastShownId == null || d.getPharmacy().getId() != lastShownId)) {
            licenseOpenedFor = null;
            chkLicenseChecked.setSelected(false);
            txtReason.setText("");
            lastShownId = d.getPharmacy().getId();
        }
        shown = d;
        if (d == null) {
            lblDetailName.setText("Select a pharmacy");
            lblDetailStatus.setText(" ");
            lblReverify.setVisible(false);
            for (JLabel v : valueLabels()) {
                v.setText("-");
            }
            updateButtons();
            return;
        }
        Pharmacy p = d.getPharmacy();
        User owner = d.getOwner();
        lblDetailName.setText(p.getName());
        lblDetailStatus.setText(p.getStatus().toString());
        UITheme.styleStatus(lblDetailStatus, UITheme.pharmacyStatusColor(p.getStatus()));
        lblReverify.setText("Re-verify: verified over " + VerificationService.REVERIFY_MONTHS + " months ago");
        lblReverify.setVisible(d.isReverificationDue());
        lblOwner.setText(owner == null ? "-" : owner.getFullName());
        lblPhone.setText(orDash(p.getPhone()));
        lblEmail.setText(owner == null ? "-" : owner.getEmail());
        lblLicense.setText(p.getLicenseNo());
        lblAddress.setText(UITheme.wrapHtml(escape(p.getAddress())
                + (d.getAreaName().isEmpty() ? "" : "<br><font color='#6B7785'>Area: " + escape(d.getAreaName()) + "</font>"),
                VALUE_WIDTH));
        lblHours.setText(DateUtil.formatOpeningHours(p.getOpenTime(), p.getCloseTime()));
        lblApplied.setText(DateUtil.toDisplayDate(p.getCreatedAt()));
        lblLocation.setText(p.getLatitude() == null || p.getLongitude() == null ? "-"
                : GeoUtil.formatCoordinate(p.getLatitude()) + ", " + GeoUtil.formatCoordinate(p.getLongitude()));
        lblVerified.setText(p.getVerifiedAt() == null ? "Not yet" : DateUtil.toDisplayDate(p.getVerifiedAt()));
        lblNote.setText(p.getRejectReason() == null ? "-"
                : UITheme.wrapHtml(escape(p.getRejectReason()), VALUE_WIDTH));
        updateButtons();
    }

    private JLabel[] valueLabels() {
        return new JLabel[]{lblOwner, lblPhone, lblEmail, lblLicense, lblAddress, lblHours, lblApplied,
            lblLocation, lblVerified, lblNote};
    }

    private void updateButtons() {
        Pharmacy p = shown == null ? null : shown.getPharmacy();
        boolean approvable = p != null && VerificationService.canApprove(p);
        boolean opened = p != null && licenseOpenedFor != null && licenseOpenedFor == p.getId();
        chkLicenseChecked.setEnabled(approvable && opened);
        if (!chkLicenseChecked.isEnabled()) {
            chkLicenseChecked.setSelected(false);
        }
        chkLicenseChecked.setToolTipText(approvable && !opened ? "Open the license document first." : null);
        btnViewLicense.setEnabled(p != null);
        btnApprove.setEnabled(approvable && chkLicenseChecked.isSelected());
        btnApprove.setText(p != null && p.getStatus() == PharmacyStatus.VERIFIED ? "Approve again" : "Approve");
        btnReject.setEnabled(p != null && VerificationService.canReject(p));
        btnSuspend.setEnabled(p != null && VerificationService.canSuspend(p));
        btnReactivate.setEnabled(p != null && VerificationService.canReactivate(p));
        txtReason.setEnabled(btnReject.isEnabled() || btnSuspend.isEnabled());
    }

    // ---------------------------------------------------------------- actions

    private void viewLicense() {
        if (shown == null) {
            return;
        }
        try {
            verificationService.openLicense(shown.getPharmacy());
            licenseOpenedFor = shown.getPharmacy().getId();
            updateButtons();
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (IOException e) {
            showError("Could not open the license file:\n" + ErrorLog.describe(e));
        }
    }

    private void approve() {
        Pharmacy p = shown.getPharmacy();
        boolean again = p.getStatus() == PharmacyStatus.VERIFIED;
        if (!confirm((again ? "Re-verify " : "Approve ") + p.getName() + "?\n"
                + (again ? "The verification date will be updated." : "Patients will be able to see this pharmacy."))) {
            return;
        }
        try {
            verificationService.approve(p.getId());
            showInfo(p.getName() + " is verified. The owner was notified.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not approve the pharmacy:\n" + ErrorLog.describe(e));
        }
        refreshAll();
    }

    /** Reject (suspend == false) or suspend; both need a reason. */
    private void rejectOrSuspend(boolean suspend) {
        Pharmacy p = shown.getPharmacy();
        String reason = txtReason.getText().trim();
        if (reason.length() < VerificationService.MIN_REASON_LENGTH) {
            fail("Write the reason (at least " + VerificationService.MIN_REASON_LENGTH
                    + " characters). The owner will see it.", txtReason);
            return;
        }
        String verb = suspend ? "Suspend" : "Reject";
        if (!confirm(verb + " " + p.getName() + "?\nReason: " + reason)) {
            return;
        }
        try {
            if (suspend) {
                verificationService.suspend(p.getId(), reason);
            } else {
                verificationService.reject(p.getId(), reason);
            }
            txtReason.setText("");
            showInfo(p.getName() + (suspend ? " is suspended." : " was rejected.") + " The owner was notified.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not " + verb.toLowerCase() + " the pharmacy:\n" + ErrorLog.describe(e));
        }
        refreshAll();
    }

    private void reactivate() {
        Pharmacy p = shown.getPharmacy();
        if (!confirm("Reactivate " + p.getName() + "?\nPatients will see it again.")) {
            return;
        }
        try {
            verificationService.reactivate(p.getId());
            showInfo(p.getName() + " is active again. The owner was notified.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not reactivate the pharmacy:\n" + ErrorLog.describe(e));
        }
        refreshAll();
    }

    // ---------------------------------------------------------------- helpers

    private static String orDash(String s) {
        return ValidationUtil.isBlank(s) ? "-" : s;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(this, message, TITLE, JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

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
        lblTitle = new javax.swing.JLabel();
        lblSubtitle = new javax.swing.JLabel();
        lblFilter = new javax.swing.JLabel();
        cmbFilterStatus = new javax.swing.JComboBox<>();
        lblSearch = new javax.swing.JLabel();
        txtSearch = new javax.swing.JTextField();
        scrPharmacies = new javax.swing.JScrollPane();
        tblPharmacies = new javax.swing.JTable();
        pnlDetails = new javax.swing.JPanel();
        lblDetailName = new javax.swing.JLabel();
        lblDetailStatus = new javax.swing.JLabel();
        lblReverify = new javax.swing.JLabel();
        lblOwnerKey = new javax.swing.JLabel();
        lblOwner = new javax.swing.JLabel();
        lblPhoneKey = new javax.swing.JLabel();
        lblPhone = new javax.swing.JLabel();
        lblEmailKey = new javax.swing.JLabel();
        lblEmail = new javax.swing.JLabel();
        lblLicenseKey = new javax.swing.JLabel();
        lblLicense = new javax.swing.JLabel();
        lblAddressKey = new javax.swing.JLabel();
        lblAddress = new javax.swing.JLabel();
        lblHoursKey = new javax.swing.JLabel();
        lblHours = new javax.swing.JLabel();
        lblAppliedKey = new javax.swing.JLabel();
        lblApplied = new javax.swing.JLabel();
        lblLocationKey = new javax.swing.JLabel();
        lblLocation = new javax.swing.JLabel();
        lblVerifiedKey = new javax.swing.JLabel();
        lblVerified = new javax.swing.JLabel();
        lblNoteKey = new javax.swing.JLabel();
        lblNote = new javax.swing.JLabel();
        btnViewLicense = new javax.swing.JButton();
        chkLicenseChecked = new javax.swing.JCheckBox();
        lblReason = new javax.swing.JLabel();
        txtReason = new javax.swing.JTextField();
        btnApprove = new javax.swing.JButton();
        btnReject = new javax.swing.JButton();
        btnSuspend = new javax.swing.JButton();
        btnReactivate = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Pharmacy Verification");
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

        lblTitle.setText("Pharmacy Verification");

        lblSubtitle.setText("Check each pharmacy's drug license before patients can see it.");

        lblFilter.setText("Status");

        cmbFilterStatus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbFilterStatusActionPerformed(evt);
            }
        });

        lblSearch.setText("Search");

        scrPharmacies.setViewportView(tblPharmacies);

        lblDetailName.setText("Select a pharmacy");

        lblDetailStatus.setText("Status");

        lblReverify.setText("Re-verify");

        lblOwnerKey.setText("Owner");

        lblOwner.setText("-");

        lblPhoneKey.setText("Phone");

        lblPhone.setText("-");

        lblEmailKey.setText("Email");

        lblEmail.setText("-");

        lblLicenseKey.setText("License no.");

        lblLicense.setText("-");

        lblAddressKey.setText("Address");

        lblAddress.setText("-");

        lblHoursKey.setText("Hours");

        lblHours.setText("-");

        lblAppliedKey.setText("Applied");

        lblApplied.setText("-");

        lblLocationKey.setText("Location");

        lblLocation.setText("-");

        lblVerifiedKey.setText("Verified");

        lblVerified.setText("-");

        lblNoteKey.setText("Reason");

        lblNote.setText("-");

        btnViewLicense.setText("View license document");
        btnViewLicense.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnViewLicenseActionPerformed(evt);
            }
        });

        chkLicenseChecked.setText("I checked the license with DGDA records");
        chkLicenseChecked.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                chkLicenseCheckedActionPerformed(evt);
            }
        });

        lblReason.setText("Reason (needed to reject or suspend)");

        btnApprove.setText("Approve");
        btnApprove.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnApproveActionPerformed(evt);
            }
        });

        btnReject.setText("Reject");
        btnReject.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRejectActionPerformed(evt);
            }
        });

        btnSuspend.setText("Suspend");
        btnSuspend.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSuspendActionPerformed(evt);
            }
        });

        btnReactivate.setText("Reactivate");
        btnReactivate.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnReactivateActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlDetailsLayout = new javax.swing.GroupLayout(pnlDetails);
        pnlDetails.setLayout(pnlDetailsLayout);
        pnlDetailsLayout.setHorizontalGroup(
            pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlDetailsLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblDetailName, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(pnlDetailsLayout.createSequentialGroup()
                        .addComponent(lblDetailStatus)
                        .addGap(10, 10, 10)
                        .addComponent(lblReverify))
                    .addGroup(pnlDetailsLayout.createSequentialGroup()
                        .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblOwnerKey)
                            .addComponent(lblPhoneKey)
                            .addComponent(lblEmailKey)
                            .addComponent(lblLicenseKey)
                            .addComponent(lblAddressKey)
                            .addComponent(lblHoursKey)
                            .addComponent(lblAppliedKey)
                            .addComponent(lblLocationKey)
                            .addComponent(lblVerifiedKey)
                            .addComponent(lblNoteKey))
                        .addGap(12, 12, 12)
                        .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblOwner, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblPhone, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblEmail, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblLicense, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblAddress, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblHours, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblApplied, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblLocation, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblVerified, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblNote, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                    .addComponent(btnViewLicense, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(chkLicenseChecked)
                    .addComponent(lblReason)
                    .addComponent(txtReason, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(pnlDetailsLayout.createSequentialGroup()
                        .addComponent(btnApprove, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnReject, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(pnlDetailsLayout.createSequentialGroup()
                        .addComponent(btnSuspend, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnReactivate, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                .addGap(16, 16, 16))
        );
        pnlDetailsLayout.setVerticalGroup(
            pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlDetailsLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(lblDetailName)
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblDetailStatus)
                    .addComponent(lblReverify))
                .addGap(14, 14, 14)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblOwnerKey)
                    .addComponent(lblOwner, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblPhoneKey)
                    .addComponent(lblPhone, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblEmailKey)
                    .addComponent(lblEmail, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblLicenseKey)
                    .addComponent(lblLicense, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblAddressKey)
                    .addComponent(lblAddress, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblHoursKey)
                    .addComponent(lblHours, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblAppliedKey)
                    .addComponent(lblApplied, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblLocationKey)
                    .addComponent(lblLocation, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblVerifiedKey)
                    .addComponent(lblVerified, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblNoteKey)
                    .addComponent(lblNote, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(14, 14, 14)
                .addComponent(btnViewLicense)
                .addGap(6, 6, 6)
                .addComponent(chkLicenseChecked)
                .addGap(10, 10, 10)
                .addComponent(lblReason)
                .addGap(4, 4, 4)
                .addComponent(txtReason, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(12, 12, 12)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnApprove)
                    .addComponent(btnReject))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnSuspend)
                    .addComponent(btnReactivate))
                .addGap(16, 16, Short.MAX_VALUE))
        );

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
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(lblFilter)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(cmbFilterStatus, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(16, 16, 16)
                                .addComponent(lblSearch)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtSearch, javax.swing.GroupLayout.DEFAULT_SIZE, 200, Short.MAX_VALUE))
                            .addComponent(scrPharmacies, javax.swing.GroupLayout.DEFAULT_SIZE, 600, Short.MAX_VALUE))
                        .addGap(20, 20, 20)
                        .addComponent(pnlDetails, javax.swing.GroupLayout.PREFERRED_SIZE, 340, javax.swing.GroupLayout.PREFERRED_SIZE)))
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
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(lblFilter)
                            .addComponent(cmbFilterStatus, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(lblSearch)
                            .addComponent(txtSearch, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(10, 10, 10)
                        .addComponent(scrPharmacies, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addComponent(pnlDetails, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void cmbFilterStatusActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbFilterStatusActionPerformed
        if (!updatingFilter) {
            reload();
        }
    }//GEN-LAST:event_cmbFilterStatusActionPerformed

    private void btnViewLicenseActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnViewLicenseActionPerformed
        viewLicense();
    }//GEN-LAST:event_btnViewLicenseActionPerformed

    private void chkLicenseCheckedActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_chkLicenseCheckedActionPerformed
        updateButtons();
    }//GEN-LAST:event_chkLicenseCheckedActionPerformed

    private void btnApproveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnApproveActionPerformed
        approve();
    }//GEN-LAST:event_btnApproveActionPerformed

    private void btnRejectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRejectActionPerformed
        rejectOrSuspend(false);
    }//GEN-LAST:event_btnRejectActionPerformed

    private void btnSuspendActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSuspendActionPerformed
        rejectOrSuspend(true);
    }//GEN-LAST:event_btnSuspendActionPerformed

    private void btnReactivateActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnReactivateActionPerformed
        reactivate();
    }//GEN-LAST:event_btnReactivateActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnApprove;
    private javax.swing.JButton btnReactivate;
    private javax.swing.JButton btnReject;
    private javax.swing.JButton btnSuspend;
    private javax.swing.JButton btnViewLicense;
    private javax.swing.JCheckBox chkLicenseChecked;
    private javax.swing.JComboBox<String> cmbFilterStatus;
    private javax.swing.JLabel lblAddress;
    private javax.swing.JLabel lblAddressKey;
    private javax.swing.JLabel lblApplied;
    private javax.swing.JLabel lblAppliedKey;
    private javax.swing.JLabel lblDetailName;
    private javax.swing.JLabel lblDetailStatus;
    private javax.swing.JLabel lblEmail;
    private javax.swing.JLabel lblEmailKey;
    private javax.swing.JLabel lblFilter;
    private javax.swing.JLabel lblHours;
    private javax.swing.JLabel lblHoursKey;
    private javax.swing.JLabel lblLicense;
    private javax.swing.JLabel lblLicenseKey;
    private javax.swing.JLabel lblLocation;
    private javax.swing.JLabel lblLocationKey;
    private javax.swing.JLabel lblNote;
    private javax.swing.JLabel lblNoteKey;
    private javax.swing.JLabel lblOwner;
    private javax.swing.JLabel lblOwnerKey;
    private javax.swing.JLabel lblPhone;
    private javax.swing.JLabel lblPhoneKey;
    private javax.swing.JLabel lblReason;
    private javax.swing.JLabel lblReverify;
    private javax.swing.JLabel lblSearch;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblVerified;
    private javax.swing.JLabel lblVerifiedKey;
    private javax.swing.JPanel pnlDetails;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrPharmacies;
    private javax.swing.JTable tblPharmacies;
    private javax.swing.JTextField txtReason;
    private javax.swing.JTextField txtSearch;
    // End of variables declaration//GEN-END:variables
}
