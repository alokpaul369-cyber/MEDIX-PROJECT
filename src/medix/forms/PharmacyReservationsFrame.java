package medix.forms;

import java.awt.Component;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import medix.model.CancelReason;
import medix.model.Reservation;
import medix.model.ReservationStatus;
import medix.model.ReservationView;
import medix.service.PharmacyReservationService;
import medix.service.PharmacyReservationService.Filter;
import medix.service.ReservationService;
import medix.service.ServiceException;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * The pharmacy's reservations: confirm, mark ready, hand over (picked up), extend once, or
 * cancel with a reason. The list refreshes every 30 seconds; "Left" is red under 10 minutes.
 * The patient's phone is masked until the reservation is confirmed.
 */
public class PharmacyReservationsFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Reservations";
    private static final int REFRESH_MILLIS = 30 * 1000;
    private static final int URGENT_MINUTES = 10;
    private static final int COL_STATUS = 7;
    private static final int COL_LEFT = 9;

    private final PharmacyReservationService service = new PharmacyReservationService();
    private DefaultTableModel tableModel;
    /** Rows in table-model order. */
    private List<ReservationView> rows = new ArrayList<ReservationView>();
    private boolean changingFilter;
    private final Timer timer;

    /** Opens the list with one reservation selected (switches the filter to All when needed). */
    public PharmacyReservationsFrame(int reservationId) {
        this();
        if (!TableUtil.selectId(tblReservations, reservationId)) {
            changingFilter = true;
            cmbFilter.setSelectedItem(Filter.ALL);
            changingFilter = false;
            reload();
            TableUtil.selectId(tblReservations, reservationId);
        }
    }

    public PharmacyReservationsFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.PHARMACY_RESERVATIONS);
        changingFilter = true;
        cmbFilter.setModel(new DefaultComboBoxModel<Filter>(Filter.values()));
        changingFilter = false;
        setupTable();
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
        UITheme.styleMuted(lblStats);
        lblTrust.setFont(UITheme.FONT_HEADING);
        UITheme.stylePrimaryButton(btnConfirm);
        UITheme.styleSuccessButton(btnReady);
        UITheme.styleSuccessButton(btnPickedUp);
        UITheme.styleDangerButton(btnCancel);
        txtCode.putClientProperty("JTextField.placeholderText", "MX-1101-0042");
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblReservations,
                "ID", "Code", "Patient", "Phone", "Medicine", "Qty", "Amount", "Status", "Hold until", "Left");
        TableUtil.setColumnWidth(tblReservations, 1, 105);
        TableUtil.setColumnWidth(tblReservations, 2, 130);
        TableUtil.setColumnWidth(tblReservations, 3, 105);
        TableUtil.setColumnWidth(tblReservations, 4, 170);
        TableUtil.setColumnWidth(tblReservations, 5, 40);
        TableUtil.centerColumn(tblReservations, 5);
        TableUtil.setColumnWidth(tblReservations, 6, 80);
        TableUtil.setColumnWidth(tblReservations, COL_STATUS, 90);
        TableUtil.setColumnWidth(tblReservations, 8, 110);
        TableUtil.setColumnWidth(tblReservations, COL_LEFT, 80);
        tblReservations.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setFont(UITheme.FONT_BODY_BOLD);
                if (!selected) {
                    setForeground(UITheme.reservationStatusColor((ReservationStatus) value));
                }
                return this;
            }
        });
        tblReservations.getColumnModel().getColumn(COL_LEFT).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                Reservation r = rows.get(table.convertRowIndexToModel(row)).getReservation();
                boolean urgent = r.getStatus().isActive() && ReservationService.minutesLeft(r) < URGENT_MINUTES;
                setFont(urgent ? UITheme.FONT_BODY_BOLD : UITheme.FONT_BODY);
                if (!selected) {
                    setForeground(urgent ? UITheme.DANGER : UITheme.TEXT);
                }
                return this;
            }
        });
        tblReservations.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
    }

    // ---------------------------------------------------------------- loading

    /** Re-reads list and numbers (keeps the selection); also run by the 30-second timer. */
    private void reload() {
        Integer keep = TableUtil.getSelectedId(tblReservations);
        try {
            rows = service.getReservations((Filter) cmbFilter.getSelectedItem());
            showStats(service.getStats());
        } catch (ServiceException e) {
            stopTimer();
            JOptionPane.showMessageDialog(this, e.getMessage(), TITLE, JOptionPane.WARNING_MESSAGE);
            return;
        } catch (SQLException e) {
            stopTimer();
            JOptionPane.showMessageDialog(this, "Could not load the reservations:\n" + ErrorLog.describe(e), TITLE,
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        TableUtil.clear(tableModel);
        for (ReservationView v : rows) {
            Reservation r = v.getReservation();
            tableModel.addRow(new Object[]{r.getId(), r.getCode(), v.getPatientName(),
                PharmacyReservationService.showFullPhone(r.getStatus()) ? v.getPatientPhone()
                : ValidationUtil.maskPhone(v.getPatientPhone()),
                v.getMedicineLabel(), r.getQuantity(),
                v.getAmount() == null ? "–" : String.format(Locale.US, "Tk %.2f", v.getAmount()),
                r.getStatus(), holdUntil(r), timeLeft(r)});
        }
        if (keep == null || !TableUtil.selectId(tblReservations, keep)) {
            tblReservations.clearSelection();
        }
        showSelected();
    }

    private void stopTimer() {
        if (timer != null) {
            timer.stop();
        }
    }

    private void showStats(PharmacyReservationService.Stats s) {
        lblSubtitle.setText(s.active + " active  ·  " + s.toConfirm + " to confirm");
        lblStats.setText("Today: " + s.completedToday + " completed  ·  " + s.expiredToday + " expired (no-show)  ·  "
                + s.cancelledByPharmacyToday + " cancelled by you");
        int diff = s.trustScore - s.trustLastWeek;
        String trend = diff > 0 ? UITheme.symbolHtml('▲', "+") + " +" + diff
                : diff < 0 ? UITheme.symbolHtml('▼', "-") + " " + diff : "= same";
        lblTrust.setText("<html>Trust score <b>" + s.trustScore + "</b>&nbsp;&nbsp;" + trend + " vs last week</html>");
        lblTrust.setForeground(diff > 0 ? UITheme.SUCCESS : diff < 0 ? UITheme.DANGER : UITheme.SIDEBAR);
    }

    private static String holdUntil(Reservation r) {
        String time = r.getExpiresAt().substring(11, 16);
        return (r.getExpiresAt().startsWith(DateUtil.today()) ? time : DateUtil.toDisplayDate(r.getExpiresAt()) + ", " + time)
                + (r.isExtended() ? " (+15)" : "");
    }

    private static String timeLeft(Reservation r) {
        if (!r.getStatus().isActive()) {
            return "–";
        }
        long m = ReservationService.minutesLeft(r);
        if (m < 0) {
            return "Ended";
        }
        return m >= 60 ? String.format("%d h %02d min", m / 60, m % 60) : m + " min";
    }

    private ReservationView selected() {
        int viewRow = tblReservations.getSelectedRow();
        return viewRow < 0 ? null : rows.get(tblReservations.convertRowIndexToModel(viewRow));
    }

    private void showSelected() {
        ReservationView v = selected();
        Reservation r = v == null ? null : v.getReservation();
        btnConfirm.setEnabled(r != null && PharmacyReservationService.canConfirm(r));
        btnReady.setEnabled(r != null && PharmacyReservationService.canMarkReady(r));
        btnPickedUp.setEnabled(r != null && PharmacyReservationService.canPickUp(r));
        btnExtend.setEnabled(r != null && PharmacyReservationService.canExtend(r));
        btnCancel.setEnabled(r != null && PharmacyReservationService.canCancel(r));
        if (r == null) {
            lblDetail.setText(rows.isEmpty() ? "No reservations to show." : "Select a reservation.");
            return;
        }
        StringBuilder sb = new StringBuilder("<html><b>").append(r.getCode()).append("</b> · ")
                .append(v.getMedicineShort()).append(" × ").append(r.getQuantity());
        if (r.getNote() != null) {
            sb.append(" · Note: ").append(r.getNote().replace("<", "&lt;"));
        }
        if (r.getCancelReason() != null) {
            sb.append(" · Cancelled: ").append(r.getCancelReason().replace("<", "&lt;"));
        } else if ("PATIENT".equals(r.getCancelledBy())) {
            sb.append(" · Cancelled by the patient");
        }
        lblDetail.setText(sb.append("</html>").toString());
    }

    // ---------------------------------------------------------------- actions

    private void find() {
        try {
            Reservation r = service.findByCode(txtCode.getText());
            Filter f = (Filter) cmbFilter.getSelectedItem();
            if (!f.includes(r.getStatus())) {
                changingFilter = true;
                cmbFilter.setSelectedItem(Filter.ALL);
                changingFilter = false;
            }
            reload();
            TableUtil.selectId(tblReservations, r.getId());
            txtCode.setText("");
        } catch (ServiceException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), TITLE, JOptionPane.WARNING_MESSAGE);
            SwingUtilities.invokeLater(txtCode::requestFocusInWindow);
        } catch (SQLException e) {
            showError(e);
        }
    }

    /** One of the simple actions; afterwards the list is reloaded and the row stays selected. */
    private interface Action {
        void run(int reservationId) throws ServiceException, SQLException;
    }

    private void act(Action action, String doneMessage) {
        ReservationView v = selected();
        if (v == null) {
            return;
        }
        try {
            action.run(v.getReservation().getId());
            reload();
            if (doneMessage != null) {
                JOptionPane.showMessageDialog(this, doneMessage, TITLE, JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (ServiceException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), TITLE, JOptionPane.WARNING_MESSAGE);
            reload();
        } catch (SQLException e) {
            showError(e);
        }
    }

    private boolean ask(String question) {
        return JOptionPane.showConfirmDialog(this, question, TITLE, JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void pickedUp() {
        ReservationView v = selected();
        if (v != null && ask("Patient showed code " + v.getReservation().getCode() + "?\n"
                + v.getMedicineShort() + " × " + v.getReservation().getQuantity() + " will leave your stock.")) {
            act(service::pickUp, v.getReservation().getCode() + " is complete. Your stock was updated.");
        }
    }

    private void extend() {
        ReservationView v = selected();
        if (v != null && ask("Add " + PharmacyReservationService.EXTEND_MINUTES + " minutes to "
                + v.getReservation().getCode() + "?\nThis can be done only once.")) {
            act(service::extend, null);
        }
    }

    private void cancelSelected() {
        ReservationView v = selected();
        if (v == null) {
            return;
        }
        String code = v.getReservation().getCode();
        CancelReason reason = (CancelReason) JOptionPane.showInputDialog(this, "Why do you cancel " + code + "?",
                TITLE, JOptionPane.QUESTION_MESSAGE, null, CancelReason.values(), CancelReason.OUT_OF_STOCK);
        if (reason == null) {
            return;
        }
        String details = null;
        if (reason == CancelReason.OTHER) {
            details = JOptionPane.showInputDialog(this, "Write the reason for the patient:", TITLE,
                    JOptionPane.QUESTION_MESSAGE);
            if (ValidationUtil.isBlank(details)) {
                return;
            }
        }
        if (!ask("Cancel " + code + "? The patient will be told: " + reason
                + (reason == CancelReason.OUT_OF_STOCK ? "\n(Out-of-stock cancellations lower your trust score.)" : ""))) {
            return;
        }
        final String why = details;
        act(id -> service.cancel(id, reason, why), code + " was cancelled and the units were released.");
    }

    private void showError(SQLException e) {
        JOptionPane.showMessageDialog(this, ErrorLog.describe(e), TITLE, JOptionPane.ERROR_MESSAGE);
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
        lblCode = new javax.swing.JLabel();
        txtCode = new javax.swing.JTextField();
        btnFind = new javax.swing.JButton();
        lblFilter = new javax.swing.JLabel();
        cmbFilter = new javax.swing.JComboBox<>();
        scrReservations = new javax.swing.JScrollPane();
        tblReservations = new javax.swing.JTable();
        lblDetail = new javax.swing.JLabel();
        btnConfirm = new javax.swing.JButton();
        btnReady = new javax.swing.JButton();
        btnPickedUp = new javax.swing.JButton();
        btnExtend = new javax.swing.JButton();
        btnCancel = new javax.swing.JButton();
        lblStats = new javax.swing.JLabel();
        lblTrust = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Reservations");
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

        lblTitle.setText("Reservations");

        lblSubtitle.setText("0 active · 0 to confirm");

        lblCode.setText("Code");

        txtCode.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtCodeActionPerformed(evt);
            }
        });

        btnFind.setText("Find");
        btnFind.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnFindActionPerformed(evt);
            }
        });

        lblFilter.setText("Show");

        cmbFilter.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbFilterActionPerformed(evt);
            }
        });

        scrReservations.setViewportView(tblReservations);

        lblDetail.setText(" ");

        btnConfirm.setText("Confirm");
        btnConfirm.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnConfirmActionPerformed(evt);
            }
        });

        btnReady.setText("Ready");
        btnReady.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnReadyActionPerformed(evt);
            }
        });

        btnPickedUp.setText("Picked up");
        btnPickedUp.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnPickedUpActionPerformed(evt);
            }
        });

        btnExtend.setText("Extend +15 min");
        btnExtend.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnExtendActionPerformed(evt);
            }
        });

        btnCancel.setText("Cancel...");
        btnCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelActionPerformed(evt);
            }
        });

        lblStats.setText(" ");

        lblTrust.setText("Trust score");

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
                        .addComponent(lblCode)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(txtCode, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnFind)
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(lblFilter)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(cmbFilter, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(scrReservations, javax.swing.GroupLayout.DEFAULT_SIZE, 960, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblDetail, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGap(12, 12, 12)
                        .addComponent(btnConfirm)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnReady)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnPickedUp)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnExtend)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnCancel))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblStats, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGap(12, 12, 12)
                        .addComponent(lblTrust)))
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
                    .addComponent(lblCode)
                    .addComponent(txtCode, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnFind)
                    .addComponent(lblFilter)
                    .addComponent(cmbFilter, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addComponent(scrReservations, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(10, 10, 10)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblDetail)
                    .addComponent(btnConfirm)
                    .addComponent(btnReady)
                    .addComponent(btnPickedUp)
                    .addComponent(btnExtend)
                    .addComponent(btnCancel))
                .addGap(12, 12, 12)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblStats)
                    .addComponent(lblTrust))
                .addGap(18, 18, 18))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void txtCodeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtCodeActionPerformed
        find();
    }//GEN-LAST:event_txtCodeActionPerformed

    private void btnFindActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnFindActionPerformed
        find();
    }//GEN-LAST:event_btnFindActionPerformed

    private void cmbFilterActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbFilterActionPerformed
        if (!changingFilter) {
            reload();
        }
    }//GEN-LAST:event_cmbFilterActionPerformed

    private void btnConfirmActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnConfirmActionPerformed
        act(service::confirm, null);
    }//GEN-LAST:event_btnConfirmActionPerformed

    private void btnReadyActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnReadyActionPerformed
        act(service::markReady, null);
    }//GEN-LAST:event_btnReadyActionPerformed

    private void btnPickedUpActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnPickedUpActionPerformed
        pickedUp();
    }//GEN-LAST:event_btnPickedUpActionPerformed

    private void btnExtendActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnExtendActionPerformed
        extend();
    }//GEN-LAST:event_btnExtendActionPerformed

    private void btnCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelActionPerformed
        cancelSelected();
    }//GEN-LAST:event_btnCancelActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCancel;
    private javax.swing.JButton btnConfirm;
    private javax.swing.JButton btnExtend;
    private javax.swing.JButton btnFind;
    private javax.swing.JButton btnPickedUp;
    private javax.swing.JButton btnReady;
    private javax.swing.JComboBox<medix.service.PharmacyReservationService.Filter> cmbFilter;
    private javax.swing.JLabel lblCode;
    private javax.swing.JLabel lblDetail;
    private javax.swing.JLabel lblFilter;
    private javax.swing.JLabel lblStats;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblTrust;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrReservations;
    private javax.swing.JTable tblReservations;
    private javax.swing.JTextField txtCode;
    // End of variables declaration//GEN-END:variables
}
