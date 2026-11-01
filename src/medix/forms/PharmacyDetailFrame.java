package medix.forms;

import java.awt.datatransfer.StringSelection;
import java.sql.SQLException;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JOptionPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import medix.engine.GeoUtil;
import medix.model.Inventory;
import medix.model.Medicine;
import medix.model.Pharmacy;
import medix.model.PharmacyOffer;
import medix.model.ReservationView;
import medix.model.Role;
import medix.service.ReservationService;
import medix.service.ServiceException;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.FormUtil;
import medix.util.MapUtil;
import medix.util.Session;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;

/**
 * One pharmacy and one medicine (opened from SearchFrame's "View &amp; reserve"): contact details,
 * the non-expired batches, and the reservation form.
 */
public class PharmacyDetailFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Pharmacy";
    private static final String DISCLAIMER = "MediX helps you find and hold medicine. It does not replace your "
            + "doctor's advice or a call to the pharmacy. In an emergency, call the pharmacy before you travel.";
    private static final String RX_NOTE = "Prescription medicine: bring your prescription. "
            + "The pharmacist may refuse without it.";

    private final ReservationService reservationService = new ReservationService();
    private final int pharmacyId;
    private final int medicineId;
    private final int areaId;
    private final Runnable onReserved;
    private PharmacyOffer offer;
    private DefaultTableModel batchModel;

    /** onReserved runs after a successful reservation (SearchFrame refreshes its results). */
    public PharmacyDetailFrame(int pharmacyId, int medicineId, int areaId, Runnable onReserved) {
        this.pharmacyId = pharmacyId;
        this.medicineId = medicineId;
        this.areaId = areaId;
        this.onReserved = onReserved;
        initComponents();
        SidebarUtil.guard(this, Role.PATIENT);
        applyTheme();
        batchModel = TableUtil.setupTable(tblBatches, "ID", "Batch", "Available", "Price", "Expiry");
        TableUtil.centerColumn(tblBatches, 2);
        TableUtil.setColumnWidth(tblBatches, 1, 60);
        TableUtil.setColumnWidth(tblBatches, 2, 70);
        TableUtil.setColumnWidth(tblBatches, 3, 70);
        TableUtil.setColumnWidth(tblBatches, 4, 95);
        cmbHold.setModel(new DefaultComboBoxModel<String>(holdLabels()));
        cmbHold.setSelectedItem(ReservationService.DEFAULT_HOLD_MINUTES + " minutes");
        spnQuantity.addChangeListener(e -> updateTotal());
        load();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleCard(pnlPharmacy);
        UITheme.styleCard(pnlReserve);
        UITheme.styleHeading(lblPharmacyName);
        UITheme.styleHeading(lblReserveTitle);
        lblVerified.setOpaque(true);
        lblVerified.setBackground(UITheme.SUCCESS);
        lblVerified.setForeground(UITheme.WHITE);
        lblVerified.setFont(UITheme.FONT_BODY_BOLD);
        lblVerified.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        UITheme.styleMuted(lblDistance);
        UITheme.styleMuted(lblTrust);
        txtPhone.setFont(UITheme.FONT_TITLE);
        txtPhone.setForeground(UITheme.SIDEBAR);
        txtPhone.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        txtPhone.setOpaque(false);
        txtPhone.setToolTipText("Select the number or use Copy phone");
        lblTotal.setFont(UITheme.FONT_TITLE);
        lblTotal.setForeground(UITheme.SIDEBAR);
        UITheme.styleWarningNote(lblRxNote);
        UITheme.styleInfoNote(lblDisclaimer);
        lblRxNote.setText(UITheme.wrapHtml(RX_NOTE, 276));
        lblDisclaimer.setText(UITheme.wrapHtml(DISCLAIMER, 276));
        chkUnderstand.setOpaque(false);
        UITheme.stylePrimaryButton(btnReserve);
        txtNote.putClientProperty("JTextField.placeholderText", "Optional, e.g. I will come after office");
    }

    private static String[] holdLabels() {
        String[] labels = new String[ReservationService.HOLD_MINUTES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = ReservationService.HOLD_MINUTES[i] + " minutes";
        }
        return labels;
    }

    private int selectedMinutes() {
        return ReservationService.HOLD_MINUTES[Math.max(0, cmbHold.getSelectedIndex())];
    }

    private void load() {
        try {
            offer = reservationService.getOffer(pharmacyId, medicineId, areaId);
        } catch (ServiceException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), TITLE, JOptionPane.WARNING_MESSAGE);
            SwingUtilities.invokeLater(this::dispose);
            return;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not load the pharmacy:\n" + ErrorLog.describe(e), TITLE,
                    JOptionPane.ERROR_MESSAGE);
            SwingUtilities.invokeLater(this::dispose);
            return;
        }
        Pharmacy p = offer.getPharmacy();
        Medicine m = offer.getMedicine();
        setTitle(TITLE + " – " + p.getName());
        lblPharmacyName.setText("<html>" + escape(p.getName()) + "</html>");
        lblAddress.setText(UITheme.wrapHtml(escape(p.getAddress())
                + (offer.getAreaName().isEmpty() ? "" : ", " + escape(offer.getAreaName())), 290));
        lblDistance.setText(GeoUtil.formatKm(offer.getDistanceKm()) + " away");
        lblHours.setText("<html>" + DateUtil.formatOpeningHours(p.getOpenTime(), p.getCloseTime())
                + "&nbsp;&nbsp;<b><font color='" + (offer.isOpenNow() ? "#2E9E5B'>Open now" : "#D64545'>Closed")
                + "</font></b></html>");
        txtPhone.setText(p.getPhone() == null ? "" : p.getPhone());
        lblTrust.setText("Trust score " + p.getTrustScore() + " / 100");
        lblReserveTitle.setText("<html>Reserve " + escape(m.getBrandName() + " " + m.getStrength()) + "</html>");
        lblRxNote.setVisible(m.isRxRequired());

        TableUtil.clear(batchModel);
        for (Inventory i : offer.getBatches()) {
            batchModel.addRow(new Object[]{i.getId(), i.getBatchNo(), Math.max(0, i.getAvailableQty()),
                String.format(Locale.US, "Tk %.2f", i.getPrice()), DateUtil.toDisplayDate(i.getExpiryDate())});
        }
        int max = Math.min(offer.getTotalAvailable(), ReservationService.MAX_UNITS);
        spnQuantity.setModel(new SpinnerNumberModel(1, 1, Math.max(1, max), 1));
        spnQuantity.setEnabled(max > 0);
        updateTotal();
        updateReserveButton();
    }

    /** quantity × price of the batch that would be used (first-expiring batch with enough units). */
    private void updateTotal() {
        if (offer == null) {
            return;
        }
        int qty = ((Number) spnQuantity.getValue()).intValue();
        Inventory batch = ReservationService.pickBatch(offer.getBatches(), qty);
        if (batch == null && !offer.getBatches().isEmpty()) {
            batch = offer.getBatches().get(0);
        }
        lblTotal.setText(batch == null ? "Tk –" : String.format(Locale.US, "Tk %.2f", qty * batch.getPrice()));
    }

    private void updateReserveButton() {
        if (offer == null) {
            btnReserve.setEnabled(false);
            return;
        }
        boolean stock = offer.getTotalAvailable() > 0;
        if (!offer.isOpenNow()) {
            btnReserve.setText("Closed now – cannot reserve");
        } else if (!stock) {
            btnReserve.setText("Not available now");
        } else {
            btnReserve.setText("Reserve for " + selectedMinutes() + " minutes");
        }
        btnReserve.setEnabled(chkUnderstand.isSelected() && offer.isOpenNow() && stock);
    }

    private void reserve() {
        int qty = FormUtil.intValue(spnQuantity);
        Integer me = Session.getUserId();
        if (me == null) {
            JOptionPane.showMessageDialog(this, "Please log in again.", TITLE, JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            ReservationView v = reservationService.reserve(me, pharmacyId, medicineId, qty, selectedMinutes(),
                    txtNote.getText());
            new ReservationConfirmFrame(v).setVisible(true);
            if (onReserved != null) {
                onReserved.run();
            }
            dispose();
        } catch (ServiceException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), TITLE, JOptionPane.WARNING_MESSAGE);
            load(); // availability may have changed
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not reserve:\n" + ErrorLog.describe(e), TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    private void copyPhone() {
        String phone = txtPhone.getText().trim();
        if (phone.isEmpty()) {
            return;
        }
        getToolkit().getSystemClipboard().setContents(new StringSelection(phone), null);
        JOptionPane.showMessageDialog(this, "Phone number copied. Call before you travel.", TITLE,
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void openMap() {
        if (offer == null) {
            return;
        }
        Pharmacy p = offer.getPharmacy();
        double lat = p.getLatitude() == null ? 0 : p.getLatitude();
        double lon = p.getLongitude() == null ? 0 : p.getLongitude();
        String where = p.getName() + " is at " + GeoUtil.formatCoordinate(lat) + ", " + GeoUtil.formatCoordinate(lon)
                + "\n" + p.getAddress() + (p.getPhone() == null ? "" : "\nPhone: " + p.getPhone());
        MapUtil.openMapOrExplain(this, btnOpenMap, TITLE, lat, lon, where);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        pnlPharmacy = new javax.swing.JPanel();
        lblPharmacyName = new javax.swing.JLabel();
        lblVerified = new javax.swing.JLabel();
        lblAddress = new javax.swing.JLabel();
        lblDistance = new javax.swing.JLabel();
        lblHours = new javax.swing.JLabel();
        txtPhone = new javax.swing.JTextField();
        btnCopyPhone = new javax.swing.JButton();
        btnOpenMap = new javax.swing.JButton();
        lblTrust = new javax.swing.JLabel();
        scrBatches = new javax.swing.JScrollPane();
        tblBatches = new javax.swing.JTable();
        pnlReserve = new javax.swing.JPanel();
        lblReserveTitle = new javax.swing.JLabel();
        lblQuantity = new javax.swing.JLabel();
        spnQuantity = new javax.swing.JSpinner();
        lblHold = new javax.swing.JLabel();
        cmbHold = new javax.swing.JComboBox<>();
        lblNote = new javax.swing.JLabel();
        txtNote = new javax.swing.JTextField();
        lblTotal = new javax.swing.JLabel();
        lblRxNote = new javax.swing.JLabel();
        lblDisclaimer = new javax.swing.JLabel();
        chkUnderstand = new javax.swing.JCheckBox();
        btnReserve = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("MediX – Pharmacy");
        setPreferredSize(new java.awt.Dimension(720, 520));
        setResizable(false);

        lblPharmacyName.setText("Pharmacy");

        lblVerified.setText("Verified");

        lblAddress.setText("Address");

        lblDistance.setText("Distance");

        lblHours.setText("Hours");

        txtPhone.setEditable(false);

        btnCopyPhone.setText("Copy phone");
        btnCopyPhone.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCopyPhoneActionPerformed(evt);
            }
        });

        btnOpenMap.setText("Open map");
        btnOpenMap.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnOpenMapActionPerformed(evt);
            }
        });

        lblTrust.setText("Trust score");

        javax.swing.GroupLayout pnlPharmacyLayout = new javax.swing.GroupLayout(pnlPharmacy);
        pnlPharmacy.setLayout(pnlPharmacyLayout);
        pnlPharmacyLayout.setHorizontalGroup(
            pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlPharmacyLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(pnlPharmacyLayout.createSequentialGroup()
                        .addComponent(lblPharmacyName, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGap(8, 8, 8)
                        .addComponent(lblVerified))
                    .addComponent(lblAddress, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblDistance)
                    .addComponent(lblHours, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(txtPhone, javax.swing.GroupLayout.DEFAULT_SIZE, 298, Short.MAX_VALUE)
                    .addGroup(pnlPharmacyLayout.createSequentialGroup()
                        .addComponent(btnCopyPhone)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnOpenMap))
                    .addComponent(lblTrust))
                .addGap(16, 16, 16))
        );
        pnlPharmacyLayout.setVerticalGroup(
            pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlPharmacyLayout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblPharmacyName)
                    .addComponent(lblVerified))
                .addGap(6, 6, 6)
                .addComponent(lblAddress)
                .addGap(4, 4, 4)
                .addComponent(lblDistance)
                .addGap(4, 4, 4)
                .addComponent(lblHours)
                .addGap(10, 10, 10)
                .addComponent(txtPhone, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnCopyPhone)
                    .addComponent(btnOpenMap))
                .addGap(8, 8, 8)
                .addComponent(lblTrust)
                .addGap(14, 14, 14))
        );

        scrBatches.setViewportView(tblBatches);

        lblReserveTitle.setText("Reserve");

        lblQuantity.setText("Quantity");

        lblHold.setText("Hold for");

        cmbHold.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbHoldActionPerformed(evt);
            }
        });

        lblNote.setText("Note for the pharmacist");

        lblTotal.setText("Tk 0.00");

        lblRxNote.setText("Prescription medicine");

        lblDisclaimer.setText("Disclaimer");

        chkUnderstand.setText("I understand");
        chkUnderstand.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                chkUnderstandActionPerformed(evt);
            }
        });

        btnReserve.setText("Reserve for 45 minutes");
        btnReserve.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnReserveActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlReserveLayout = new javax.swing.GroupLayout(pnlReserve);
        pnlReserve.setLayout(pnlReserveLayout);
        pnlReserveLayout.setHorizontalGroup(
            pnlReserveLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlReserveLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlReserveLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblReserveTitle, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(pnlReserveLayout.createSequentialGroup()
                        .addGroup(pnlReserveLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblQuantity)
                            .addComponent(spnQuantity, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(12, 12, 12)
                        .addGroup(pnlReserveLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblHold)
                            .addComponent(cmbHold, javax.swing.GroupLayout.DEFAULT_SIZE, 150, Short.MAX_VALUE)))
                    .addComponent(lblNote)
                    .addComponent(txtNote, javax.swing.GroupLayout.DEFAULT_SIZE, 304, Short.MAX_VALUE)
                    .addComponent(lblTotal)
                    .addComponent(lblRxNote, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblDisclaimer, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(chkUnderstand)
                    .addComponent(btnReserve, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(16, 16, 16))
        );
        pnlReserveLayout.setVerticalGroup(
            pnlReserveLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlReserveLayout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addComponent(lblReserveTitle)
                .addGap(12, 12, 12)
                .addGroup(pnlReserveLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblQuantity)
                    .addComponent(lblHold))
                .addGap(4, 4, 4)
                .addGroup(pnlReserveLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(spnQuantity, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(cmbHold, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addComponent(lblNote)
                .addGap(4, 4, 4)
                .addComponent(txtNote, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(lblTotal)
                .addGap(10, 10, 10)
                .addComponent(lblRxNote)
                .addGap(8, 8, 8)
                .addComponent(lblDisclaimer)
                .addGap(6, 6, 6)
                .addComponent(chkUnderstand)
                .addGap(8, 8, Short.MAX_VALUE)
                .addComponent(btnReserve, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(14, 14, 14))
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(pnlPharmacy, javax.swing.GroupLayout.PREFERRED_SIZE, 330, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(scrBatches, javax.swing.GroupLayout.PREFERRED_SIZE, 330, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(12, 12, 12)
                .addComponent(pnlReserve, javax.swing.GroupLayout.PREFERRED_SIZE, 336, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(14, 14, Short.MAX_VALUE))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addComponent(pnlPharmacy, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(scrBatches, javax.swing.GroupLayout.DEFAULT_SIZE, 120, Short.MAX_VALUE)
                .addGap(14, 14, 14))
            .addGroup(layout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addComponent(pnlReserve, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(14, 14, 14))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void btnCopyPhoneActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCopyPhoneActionPerformed
        copyPhone();
    }//GEN-LAST:event_btnCopyPhoneActionPerformed

    private void btnOpenMapActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnOpenMapActionPerformed
        openMap();
    }//GEN-LAST:event_btnOpenMapActionPerformed

    private void cmbHoldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbHoldActionPerformed
        updateReserveButton();
    }//GEN-LAST:event_cmbHoldActionPerformed

    private void chkUnderstandActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_chkUnderstandActionPerformed
        updateReserveButton();
    }//GEN-LAST:event_chkUnderstandActionPerformed

    private void btnReserveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnReserveActionPerformed
        reserve();
    }//GEN-LAST:event_btnReserveActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCopyPhone;
    private javax.swing.JButton btnOpenMap;
    private javax.swing.JButton btnReserve;
    private javax.swing.JCheckBox chkUnderstand;
    private javax.swing.JComboBox<String> cmbHold;
    private javax.swing.JLabel lblAddress;
    private javax.swing.JLabel lblDisclaimer;
    private javax.swing.JLabel lblDistance;
    private javax.swing.JLabel lblHold;
    private javax.swing.JLabel lblHours;
    private javax.swing.JLabel lblNote;
    private javax.swing.JLabel lblPharmacyName;
    private javax.swing.JLabel lblQuantity;
    private javax.swing.JLabel lblReserveTitle;
    private javax.swing.JLabel lblRxNote;
    private javax.swing.JLabel lblTotal;
    private javax.swing.JLabel lblTrust;
    private javax.swing.JLabel lblVerified;
    private javax.swing.JPanel pnlPharmacy;
    private javax.swing.JPanel pnlReserve;
    private javax.swing.JScrollPane scrBatches;
    private javax.swing.JSpinner spnQuantity;
    private javax.swing.JTable tblBatches;
    private javax.swing.JTextField txtNote;
    private javax.swing.JTextField txtPhone;
    // End of variables declaration//GEN-END:variables
}
