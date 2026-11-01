package medix.forms;

import java.awt.Component;
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
import medix.model.PharmacyStatus;
import medix.model.Report;
import medix.model.ReportStatus;
import medix.model.ReportView;
import medix.service.ReportService;
import medix.service.ServiceException;
import medix.service.TrustScoreService;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;

/**
 * Admin: patient reports (disputes) about pharmacies. Uphold (RESOLVED, trust score −10 for
 * 30 days, reporter and pharmacy notified), Dismiss (reporter notified) or go to the suspend
 * flow of Pharmacy Verification.
 */
public class ReportsFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Reports";
    private static final int COL_STATUS = 6;
    /** Width of the value column in the detail card (card 330 − padding − key column). */
    private static final int VALUE_WIDTH = 200;

    private final ReportService reportService = new ReportService();
    private DefaultTableModel tableModel;
    /** Rows in table-model order. */
    private List<ReportView> rows = new ArrayList<ReportView>();
    private ReportView shown;
    private boolean updatingFilter;

    /** Opens the list with one report selected (switches the filter to All when needed). */
    public ReportsFrame(int reportId) {
        this();
        if (!TableUtil.selectId(tblReports, reportId)) {
            cmbFilterStatus.setSelectedIndex(ReportStatus.values().length); // All
            TableUtil.selectId(tblReports, reportId);
        }
    }

    public ReportsFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.ADMIN_REPORTS);
        setupTable();
        updateFilterItems();
        reload();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.styleCard(pnlDetails);
        UITheme.styleHeading(lblDetailTitle);
        for (JLabel key : new JLabel[]{lblReporterKey, lblPharmacyKey, lblTrustKey, lblReservationKey, lblDetailsKey}) {
            UITheme.styleMuted(key);
        }
        lblAdminNote.setFont(UITheme.FONT_BODY_BOLD);
        txaDetails.setFont(UITheme.FONT_BODY);
        txaDetails.setBackground(UITheme.BACKGROUND);
        txtAdminNote.putClientProperty("JTextField.placeholderText", "e.g. Pharmacy confirmed the stock was wrong");
        UITheme.styleDangerButton(btnUphold);
        UITheme.stylePrimaryButton(btnDismiss);
        UITheme.styleWarningButton(btnSuspendPharmacy);
        btnUphold.setToolTipText("The pharmacy loses " + TrustScoreService.UPHELD_REPORT_PENALTY
                + " trust points for " + TrustScoreService.WINDOW_DAYS + " days.");
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblReports, "ID", "Date", "Reporter", "Pharmacy", "Reason", "Reservation",
                "Status");
        TableUtil.setColumnWidth(tblReports, 1, 88);
        TableUtil.setColumnWidth(tblReports, 2, 95);
        TableUtil.setColumnWidth(tblReports, 3, 100);
        TableUtil.setColumnWidth(tblReports, 4, 115);
        TableUtil.setColumnWidth(tblReports, 5, 98);
        TableUtil.setColumnWidth(tblReports, COL_STATUS, 72);
        tblReports.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setFont(UITheme.FONT_BODY_BOLD);
                if (!selected && value instanceof ReportStatus) {
                    setForeground(statusColor((ReportStatus) value));
                }
                return this;
            }
        });
        tblReports.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
    }

    private static java.awt.Color statusColor(ReportStatus status) {
        switch (status) {
            case OPEN:
                return UITheme.WARNING;
            case RESOLVED:
                return UITheme.DANGER;
            default:
                return UITheme.TEXT_MUTED;
        }
    }

    // ---------------------------------------------------------------- loading

    /** Filter items: "Open (n)", Upheld, Dismissed, All. Keeps the selection. */
    private void updateFilterItems() {
        int selected = Math.max(0, cmbFilterStatus.getSelectedIndex());
        int open;
        try {
            open = reportService.countOpen();
        } catch (SQLException e) {
            open = 0;
        }
        updatingFilter = true;
        cmbFilterStatus.setModel(new DefaultComboBoxModel<String>(new String[]{
            "Open (" + open + ")", ReportStatus.RESOLVED.toString(), ReportStatus.DISMISSED.toString(), "All"}));
        cmbFilterStatus.setSelectedIndex(selected);
        updatingFilter = false;
        SidebarUtil.updateReportsCount(open);
    }

    /** null = All */
    private ReportStatus selectedFilter() {
        int i = cmbFilterStatus.getSelectedIndex();
        return i >= 0 && i < ReportStatus.values().length ? ReportStatus.values()[i] : null;
    }

    private void reload() {
        Integer keep = shown == null ? null : shown.getReport().getId();
        try {
            rows = reportService.getReports(selectedFilter());
        } catch (ServiceException e) {
            rows = new ArrayList<ReportView>();
            showWarning(e.getMessage());
        } catch (SQLException e) {
            rows = new ArrayList<ReportView>();
            showError("Could not load the reports:\n" + ErrorLog.describe(e));
        }
        TableUtil.clear(tableModel);
        for (ReportView v : rows) {
            Report r = v.getReport();
            tableModel.addRow(new Object[]{r.getId(), DateUtil.toDisplayDate(r.getCreatedAt()), v.getReporterName(),
                v.getPharmacyName(), r.getReason(), v.getReservationCode() == null ? "-" : v.getReservationCode(),
                r.getStatus()});
        }
        if (keep == null || !TableUtil.selectId(tblReports, keep)) {
            tblReports.clearSelection();
            showDetails(null);
        }
    }

    private void refreshAll() {
        updateFilterItems();
        reload();
        showSelected();
    }

    // ---------------------------------------------------------------- details

    private void showSelected() {
        Integer id = TableUtil.getSelectedId(tblReports);
        ReportView v = null;
        if (id != null) {
            try {
                v = reportService.getReport(id);
            } catch (ServiceException e) {
                showWarning(e.getMessage());
            } catch (SQLException e) {
                showError("Could not load the report:\n" + ErrorLog.describe(e));
            }
        }
        showDetails(v);
    }

    private void showDetails(ReportView v) {
        boolean other = v == null || shown == null || v.getReport().getId() != shown.getReport().getId();
        shown = v;
        if (v == null) {
            lblDetailTitle.setText("Select a report");
            lblDetailStatus.setText(" ");
            for (JLabel value : new JLabel[]{lblReporter, lblPharmacy, lblTrust, lblReservation}) {
                value.setText("-");
            }
            txaDetails.setText("");
            txtAdminNote.setText("");
            updateButtons();
            return;
        }
        Report r = v.getReport();
        lblDetailTitle.setText("Report #" + r.getId() + " · " + r.getReason());
        String when = r.getResolvedAt() == null ? "" : " on " + DateUtil.toDisplayDate(r.getResolvedAt());
        lblDetailStatus.setText(r.getStatus() + when + " · reported " + DateUtil.toDisplay(r.getCreatedAt()));
        UITheme.styleStatus(lblDetailStatus, statusColor(r.getStatus()));
        lblReporter.setText(UITheme.wrapHtml(escape(v.getReporterName()) + "<br><font color='#6B7785'>"
                + escape(v.getReporterEmail()) + "</font>", VALUE_WIDTH));
        lblPharmacy.setText(UITheme.wrapHtml(escape(v.getPharmacyName()) + " <font color='"
                + UITheme.hex(UITheme.pharmacyStatusColor(v.getPharmacyStatus())) + "'>(" + v.getPharmacyStatus()
                + ")</font>", VALUE_WIDTH));
        lblTrust.setText(trustText(v));
        lblReservation.setText(v.getReservationSummary() == null ? "-"
                : UITheme.wrapHtml("<b>" + escape(v.getReservationCode()) + "</b><br>"
                        + escape(v.getReservationSummary()), VALUE_WIDTH));
        txaDetails.setText(r.getDetails());
        txaDetails.setCaretPosition(0);
        if (r.getStatus() != ReportStatus.OPEN) {
            txtAdminNote.setText(r.getAdminNote() == null ? "" : r.getAdminNote());
        } else if (other) {
            txtAdminNote.setText("");
        }
        updateButtons();
    }

    /** "75 · 2 upheld reports before" */
    private String trustText(ReportView v) {
        int upheld;
        try {
            upheld = reportService.countUpheld(v.getReport().getPharmacyId());
        } catch (SQLException e) {
            return String.valueOf(v.getPharmacyTrustScore());
        }
        if (v.getReport().getStatus() == ReportStatus.RESOLVED) {
            upheld--; // this one does not count as "before"
        }
        return "<html><b>" + v.getPharmacyTrustScore() + "</b> · " + (upheld == 0 ? "no" : String.valueOf(upheld))
                + " upheld report" + (upheld == 1 ? "" : "s") + " before</html>";
    }

    private void updateButtons() {
        boolean open = shown != null && shown.getReport().getStatus() == ReportStatus.OPEN;
        btnUphold.setEnabled(open);
        btnDismiss.setEnabled(open);
        txtAdminNote.setEditable(open);
        txtAdminNote.setEnabled(shown != null);
        btnSuspendPharmacy.setEnabled(shown != null && shown.getPharmacyStatus() == PharmacyStatus.VERIFIED);
    }

    // ---------------------------------------------------------------- actions

    private void uphold() {
        String note = txtAdminNote.getText().trim();
        if (note.length() < ReportService.MIN_ADMIN_NOTE) {
            fail("Write a short admin note (at least " + ReportService.MIN_ADMIN_NOTE
                    + " characters). The patient will see it.", txtAdminNote);
            return;
        }
        if (!confirm("Uphold report #" + shown.getReport().getId() + " about " + shown.getPharmacyName() + "?\n"
                + "The pharmacy loses " + TrustScoreService.UPHELD_REPORT_PENALTY + " trust points for "
                + TrustScoreService.WINDOW_DAYS + " days. The patient and the pharmacy are notified.")) {
            return;
        }
        try {
            int before = shown.getPharmacyTrustScore();
            int after = reportService.uphold(shown.getReport().getId(), note);
            showInfo("Report upheld. Trust score of " + shown.getPharmacyName() + ": " + before + " → " + after + ".");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not uphold the report:\n" + ErrorLog.describe(e));
        }
        refreshAll();
    }

    private void dismiss() {
        String note = txtAdminNote.getText().trim();
        if (note.length() < ReportService.MIN_ADMIN_NOTE) {
            fail("Write why the report is dismissed (at least " + ReportService.MIN_ADMIN_NOTE
                    + " characters). The patient will see it.", txtAdminNote);
            return;
        }
        if (!confirm("Dismiss report #" + shown.getReport().getId() + "?\nThe patient is told why.")) {
            return;
        }
        try {
            reportService.dismiss(shown.getReport().getId(), note);
            showInfo("Report dismissed. The patient was notified.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not dismiss the report:\n" + ErrorLog.describe(e));
        }
        refreshAll();
    }

    /** Opens Pharmacy Verification with the pharmacy selected and the reason filled in. */
    private void suspendPharmacy() {
        Report r = shown.getReport();
        String note = txtAdminNote.getText().trim();
        String reason = "Patient report #" + r.getId() + " (" + r.getReason() + ")" + (note.isEmpty() ? "" : ": " + note);
        new VerificationFrame(r.getPharmacyId(), reason).setVisible(true);
        dispose();
    }

    // ---------------------------------------------------------------- helpers

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
        scrReports = new javax.swing.JScrollPane();
        tblReports = new javax.swing.JTable();
        pnlDetails = new javax.swing.JPanel();
        lblDetailTitle = new javax.swing.JLabel();
        lblDetailStatus = new javax.swing.JLabel();
        lblReporterKey = new javax.swing.JLabel();
        lblReporter = new javax.swing.JLabel();
        lblPharmacyKey = new javax.swing.JLabel();
        lblPharmacy = new javax.swing.JLabel();
        lblTrustKey = new javax.swing.JLabel();
        lblTrust = new javax.swing.JLabel();
        lblReservationKey = new javax.swing.JLabel();
        lblReservation = new javax.swing.JLabel();
        lblDetailsKey = new javax.swing.JLabel();
        scrDetails = new javax.swing.JScrollPane();
        txaDetails = new javax.swing.JTextArea();
        lblAdminNote = new javax.swing.JLabel();
        txtAdminNote = new javax.swing.JTextField();
        btnUphold = new javax.swing.JButton();
        btnDismiss = new javax.swing.JButton();
        btnSuspendPharmacy = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Reports");
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

        lblTitle.setText("Reports");

        lblSubtitle.setText("Patient complaints about pharmacies. An upheld report lowers the pharmacy's trust score.");

        lblFilter.setText("Status");

        cmbFilterStatus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbFilterStatusActionPerformed(evt);
            }
        });

        scrReports.setViewportView(tblReports);

        lblDetailTitle.setText("Select a report");

        lblDetailStatus.setText("Status");

        lblReporterKey.setText("Reporter");

        lblReporter.setText("-");

        lblPharmacyKey.setText("Pharmacy");

        lblPharmacy.setText("-");

        lblTrustKey.setText("Trust score");

        lblTrust.setText("-");

        lblReservationKey.setText("Reservation");

        lblReservation.setText("-");

        lblDetailsKey.setText("What the patient wrote");

        txaDetails.setColumns(20);
        txaDetails.setEditable(false);
        txaDetails.setLineWrap(true);
        txaDetails.setRows(5);
        txaDetails.setWrapStyleWord(true);

        scrDetails.setViewportView(txaDetails);

        lblAdminNote.setText("Admin note (the patient sees it)");

        btnUphold.setText("Uphold");
        btnUphold.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnUpholdActionPerformed(evt);
            }
        });

        btnDismiss.setText("Dismiss");
        btnDismiss.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnDismissActionPerformed(evt);
            }
        });

        btnSuspendPharmacy.setText("Suspend pharmacy…");
        btnSuspendPharmacy.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSuspendPharmacyActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlDetailsLayout = new javax.swing.GroupLayout(pnlDetails);
        pnlDetails.setLayout(pnlDetailsLayout);
        pnlDetailsLayout.setHorizontalGroup(
            pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlDetailsLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblDetailTitle, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblDetailStatus, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(pnlDetailsLayout.createSequentialGroup()
                        .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblReporterKey)
                            .addComponent(lblPharmacyKey)
                            .addComponent(lblTrustKey)
                            .addComponent(lblReservationKey))
                        .addGap(12, 12, 12)
                        .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblReporter, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblPharmacy, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblTrust, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblReservation, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                    .addComponent(lblDetailsKey)
                    .addComponent(scrDetails, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblAdminNote)
                    .addComponent(txtAdminNote, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(pnlDetailsLayout.createSequentialGroup()
                        .addComponent(btnUphold, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnDismiss, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addComponent(btnSuspendPharmacy, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(16, 16, 16))
        );
        pnlDetailsLayout.setVerticalGroup(
            pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlDetailsLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(lblDetailTitle)
                .addGap(6, 6, 6)
                .addComponent(lblDetailStatus)
                .addGap(14, 14, 14)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblReporterKey)
                    .addComponent(lblReporter, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblPharmacyKey)
                    .addComponent(lblPharmacy, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblTrustKey)
                    .addComponent(lblTrust, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblReservationKey)
                    .addComponent(lblReservation, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(14, 14, 14)
                .addComponent(lblDetailsKey)
                .addGap(4, 4, 4)
                .addComponent(scrDetails, javax.swing.GroupLayout.DEFAULT_SIZE, 110, Short.MAX_VALUE)
                .addGap(12, 12, 12)
                .addComponent(lblAdminNote)
                .addGap(4, 4, 4)
                .addComponent(txtAdminNote, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(12, 12, 12)
                .addGroup(pnlDetailsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnUphold)
                    .addComponent(btnDismiss))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnSuspendPharmacy)
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
                    .addComponent(lblTitle)
                    .addComponent(lblSubtitle)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(lblFilter)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(cmbFilterStatus, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addComponent(scrReports, javax.swing.GroupLayout.DEFAULT_SIZE, 610, Short.MAX_VALUE))
                        .addGap(20, 20, 20)
                        .addComponent(pnlDetails, javax.swing.GroupLayout.PREFERRED_SIZE, 330, javax.swing.GroupLayout.PREFERRED_SIZE)))
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
                            .addComponent(cmbFilterStatus, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(10, 10, 10)
                        .addComponent(scrReports, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
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

    private void btnUpholdActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUpholdActionPerformed
        uphold();
    }//GEN-LAST:event_btnUpholdActionPerformed

    private void btnDismissActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDismissActionPerformed
        dismiss();
    }//GEN-LAST:event_btnDismissActionPerformed

    private void btnSuspendPharmacyActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSuspendPharmacyActionPerformed
        suspendPharmacy();
    }//GEN-LAST:event_btnSuspendPharmacyActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnDismiss;
    private javax.swing.JButton btnSuspendPharmacy;
    private javax.swing.JButton btnUphold;
    private javax.swing.JComboBox<String> cmbFilterStatus;
    private javax.swing.JLabel lblAdminNote;
    private javax.swing.JLabel lblDetailStatus;
    private javax.swing.JLabel lblDetailTitle;
    private javax.swing.JLabel lblDetailsKey;
    private javax.swing.JLabel lblFilter;
    private javax.swing.JLabel lblPharmacy;
    private javax.swing.JLabel lblPharmacyKey;
    private javax.swing.JLabel lblReporter;
    private javax.swing.JLabel lblReporterKey;
    private javax.swing.JLabel lblReservation;
    private javax.swing.JLabel lblReservationKey;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblTrust;
    private javax.swing.JLabel lblTrustKey;
    private javax.swing.JPanel pnlDetails;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrDetails;
    private javax.swing.JScrollPane scrReports;
    private javax.swing.JTable tblReports;
    private javax.swing.JTextArea txaDetails;
    private javax.swing.JTextField txtAdminNote;
    // End of variables declaration//GEN-END:variables
}
