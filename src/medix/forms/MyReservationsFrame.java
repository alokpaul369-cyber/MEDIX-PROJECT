package medix.forms;

import java.awt.Component;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import medix.model.Reservation;
import medix.model.ReservationStatus;
import medix.model.ReservationView;
import medix.service.ReservationService;
import medix.service.ServiceException;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.Session;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;

/**
 * The patient's reservations. "Time left" counts down every 30 seconds (the list is re-read so
 * changes made by the pharmacy show up too) and turns red under 10 minutes.
 */
public class MyReservationsFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – My Reservations";
    private static final int REFRESH_MILLIS = 30 * 1000;
    private static final int URGENT_MINUTES = 10;
    private static final int COL_STATUS = 5;
    private static final int COL_TIME_LEFT = 7;

    private final ReservationService reservationService = new ReservationService();
    private DefaultTableModel tableModel;
    /** Rows in table-model order. */
    private List<ReservationView> rows = new ArrayList<ReservationView>();
    private final Timer timer;

    /** Opens the list with one reservation selected (e.g. from a notification). */
    public MyReservationsFrame(int reservationId) {
        this();
        TableUtil.selectId(tblReservations, reservationId);
    }

    public MyReservationsFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.PATIENT_RESERVATIONS);
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
        UITheme.styleDangerButton(btnCancelReservation);
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblReservations,
                "ID", "Code", "Medicine", "Qty", "Pharmacy", "Status", "Hold until", "Time left");
        TableUtil.setColumnWidth(tblReservations, 1, 110);
        TableUtil.setColumnWidth(tblReservations, 2, 210);
        TableUtil.setColumnWidth(tblReservations, 3, 45);
        TableUtil.centerColumn(tblReservations, 3);
        TableUtil.setColumnWidth(tblReservations, 4, 200);
        TableUtil.setColumnWidth(tblReservations, COL_STATUS, 100);
        TableUtil.setColumnWidth(tblReservations, 6, 120);
        TableUtil.setColumnWidth(tblReservations, COL_TIME_LEFT, 100);
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
        tblReservations.getColumnModel().getColumn(COL_TIME_LEFT).setCellRenderer(new DefaultTableCellRenderer() {
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

    /** Re-reads the list (keeps the selection); also called by the 30-second timer. */
    private void reload() {
        Integer keep = TableUtil.getSelectedId(tblReservations);
        Integer me = Session.getUserId();
        try {
            rows = me == null ? new ArrayList<ReservationView>() : reservationService.getMyReservations(me);
        } catch (SQLException e) {
            if (timer != null) {
                timer.stop();
            }
            JOptionPane.showMessageDialog(this, "Could not load your reservations:\n" + ErrorLog.describe(e), TITLE,
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        TableUtil.clear(tableModel);
        int active = 0;
        for (ReservationView v : rows) {
            Reservation r = v.getReservation();
            if (r.getStatus().isActive()) {
                active++;
            }
            tableModel.addRow(new Object[]{r.getId(), r.getCode(), v.getMedicineLabel(), r.getQuantity(),
                v.getPharmacyName(), r.getStatus(), holdUntil(r.getExpiresAt()), timeLeft(r)});
        }
        lblSubtitle.setText(active + " active of max " + ReservationService.MAX_ACTIVE_PER_PATIENT
                + "  ·  Show the code at the counter before the hold time ends. Time left updates every 30 seconds.");
        if (keep == null || !TableUtil.selectId(tblReservations, keep)) {
            tblReservations.clearSelection();
        }
        showSelected();
    }

    /** "17:27" today, otherwise "02 Nov 2026, 17:27". */
    private static String holdUntil(String expiresAt) {
        String time = expiresAt.substring(11, 16);
        return expiresAt.startsWith(DateUtil.today()) ? time : DateUtil.toDisplayDate(expiresAt) + ", " + time;
    }

    /** "32 min", "1 h 05 min", "Ended" for active reservations; "–" otherwise. */
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
        btnCancelReservation.setEnabled(v != null && v.getReservation().getStatus().isActive());
        btnReportProblem.setEnabled(v != null);
        if (v == null) {
            lblDetail.setText(rows.isEmpty() ? "No reservations yet. Search a medicine and choose View & reserve."
                    : "Select a reservation to cancel it or report a problem.");
            return;
        }
        Reservation r = v.getReservation();
        lblDetail.setText("<html><b>" + r.getCode() + "</b> · " + v.getMedicineShort() + " × " + r.getQuantity()
                + " at " + v.getPharmacyName() + (v.getPharmacyPhone() == null ? "" : " · " + v.getPharmacyPhone())
                + (v.getAmount() == null ? "" : String.format(Locale.US, " · Tk %.2f", v.getAmount()))
                + (r.getNote() == null ? "" : " · Note: " + r.getNote().replace("<", "&lt;")) + "</html>");
    }

    private void cancelSelected() {
        ReservationView v = selected();
        if (v == null) {
            return;
        }
        Reservation r = v.getReservation();
        int answer = JOptionPane.showConfirmDialog(this, "Cancel reservation " + r.getCode() + " ("
                + v.getMedicineShort() + " × " + r.getQuantity() + ")?\nThe units go back to the pharmacy.",
                TITLE, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            reservationService.cancel(r.getId(), Session.getUserId());
            reload();
            JOptionPane.showMessageDialog(this, "Reservation " + r.getCode() + " was cancelled.", TITLE,
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (ServiceException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), TITLE, JOptionPane.WARNING_MESSAGE);
            reload();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not cancel:\n" + ErrorLog.describe(e), TITLE, JOptionPane.ERROR_MESSAGE);
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
        scrReservations = new javax.swing.JScrollPane();
        tblReservations = new javax.swing.JTable();
        lblDetail = new javax.swing.JLabel();
        btnReportProblem = new javax.swing.JButton();
        btnCancelReservation = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – My Reservations");
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

        lblTitle.setText("My Reservations");

        lblSubtitle.setText("Show the code at the counter before the hold time ends.");

        scrReservations.setViewportView(tblReservations);

        lblDetail.setText(" ");

        btnReportProblem.setText("Report a problem");
        btnReportProblem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnReportProblemActionPerformed(evt);
            }
        });

        btnCancelReservation.setText("Cancel reservation");
        btnCancelReservation.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelReservationActionPerformed(evt);
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
                    .addComponent(scrReservations, javax.swing.GroupLayout.DEFAULT_SIZE, 960, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblDetail, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGap(12, 12, 12)
                        .addComponent(btnReportProblem)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnCancelReservation)))
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
                .addComponent(scrReservations, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(10, 10, 10)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblDetail)
                    .addComponent(btnReportProblem)
                    .addComponent(btnCancelReservation))
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void btnReportProblemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnReportProblemActionPerformed
        ReservationView v = selected();
        if (v != null) {
            new ReportFrame(v.getReservation().getId()).setVisible(true);
        }
    }//GEN-LAST:event_btnReportProblemActionPerformed

    private void btnCancelReservationActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelReservationActionPerformed
        cancelSelected();
    }//GEN-LAST:event_btnCancelReservationActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCancelReservation;
    private javax.swing.JButton btnReportProblem;
    private javax.swing.JLabel lblDetail;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrReservations;
    private javax.swing.JTable tblReservations;
    // End of variables declaration//GEN-END:variables
}
