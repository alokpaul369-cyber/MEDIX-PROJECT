package medix.forms;

import java.sql.SQLException;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import medix.model.Inventory;
import medix.model.InventoryItem;
import medix.model.Role;
import medix.service.InventoryService;
import medix.service.ServiceException;
import medix.util.ErrorLog;
import medix.util.FormUtil;
import medix.util.SidebarUtil;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * Small window opened from InventoryFrame for one batch:
 * Stock in (quantity + n), Sold (quantity − n, never below the reserved units) or
 * Adjust (quantity = the counted number, not below the reserved units).
 */
public class StockDialogFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Stock In / Out";

    private final InventoryService inventoryService = new InventoryService();
    private final int inventoryId;
    private final Runnable onSaved;
    private InventoryItem item;

    /** onSaved runs after a successful change (InventoryFrame reloads its table). */
    public StockDialogFrame(int inventoryId, Runnable onSaved) {
        this.inventoryId = inventoryId;
        this.onSaved = onSaved;
        initComponents();
        SidebarUtil.guard(this, Role.PHARMACY);
        applyTheme();
        spnQuantity.addChangeListener(e -> updateHint());
        if (loadItem()) {
            updateMode();
        }
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.stylePrimaryButton(btnSave);
        lblQuantity.setFont(UITheme.FONT_BODY_BOLD);
        UITheme.styleMuted(lblHint);
        rdoStockIn.setOpaque(false);
        rdoSold.setOpaque(false);
        rdoAdjust.setOpaque(false);
        getRootPane().setDefaultButton(btnSave);
    }

    private boolean loadItem() {
        try {
            item = inventoryService.getItem(inventoryId);
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not load the batch:\n" + ErrorLog.describe(e));
        }
        if (item == null) {
            btnSave.setEnabled(false);
            lblItem.setText("Batch not found");
            return false;
        }
        Inventory inv = item.getInventory();
        lblItem.setText(UITheme.wrapHtml("<b>" + item.getMedicineLabel() + "</b> · batch "
                + inv.getBatchNo() + "<br><font color='#6B7785'>Quantity " + inv.getQuantity() + " · Reserved "
                + inv.getReservedQty() + " · Available " + item.getAvailable() + "</font>", 276));
        return true;
    }

    /** Label, spinner range and note hint for the chosen action. */
    private void updateMode() {
        Inventory inv = item.getInventory();
        if (rdoSold.isSelected()) {
            lblQuantity.setText("Units sold *");
            int max = Math.max(1, item.getAvailable());
            spnQuantity.setModel(new SpinnerNumberModel(1, 1, max, 1));
            spnQuantity.setEnabled(item.getAvailable() > 0);
            txtNote.putClientProperty("JTextField.placeholderText", "Optional, e.g. counter sale");
        } else if (rdoAdjust.isSelected()) {
            lblQuantity.setText("New quantity (what you counted) *");
            spnQuantity.setModel(new SpinnerNumberModel(inv.getQuantity(), inv.getReservedQty(),
                    Math.max(inv.getQuantity(), InventoryService.MAX_UNITS), 1));
            spnQuantity.setEnabled(true);
            txtNote.putClientProperty("JTextField.placeholderText", "Required: why? e.g. counted, damaged");
        } else {
            lblQuantity.setText("Units received *");
            spnQuantity.setModel(new SpinnerNumberModel(1, 1, InventoryService.MAX_UNITS, 1));
            spnQuantity.setEnabled(true);
            txtNote.putClientProperty("JTextField.placeholderText", "Optional, e.g. supplier invoice no.");
        }
        txtNote.repaint();
        updateHint();
    }

    private void updateHint() {
        if (item == null) {
            return;
        }
        Inventory inv = item.getInventory();
        int n = ((Number) spnQuantity.getValue()).intValue();
        int quantity;
        if (rdoSold.isSelected()) {
            if (item.getAvailable() <= 0) {
                lblHint.setText("Nothing is available to sell (" + inv.getReservedQty() + " reserved).");
                btnSave.setEnabled(false);
                return;
            }
            quantity = inv.getQuantity() - n;
        } else if (rdoAdjust.isSelected()) {
            quantity = n;
        } else {
            quantity = inv.getQuantity() + n;
        }
        btnSave.setEnabled(true);
        lblHint.setText("After saving: quantity " + quantity + ", available " + (quantity - inv.getReservedQty()));
    }

    private void save() {
        int n = FormUtil.intValue(spnQuantity);
        String note = txtNote.getText();
        try {
            if (rdoSold.isSelected()) {
                if (n > item.getAvailable()) {
                    fail("Only " + item.getAvailable() + " unit(s) are available to sell. Reserved units are kept for patients.",
                            spnQuantity);
                    return;
                }
                inventoryService.sell(inventoryId, n, note);
            } else if (rdoAdjust.isSelected()) {
                if (ValidationUtil.isBlank(note)) {
                    fail("Write why the quantity is adjusted (e.g. counted, damaged).", txtNote);
                    return;
                }
                inventoryService.adjust(inventoryId, n, note);
            } else {
                inventoryService.stockIn(inventoryId, n, note);
            }
        } catch (ServiceException e) {
            fail(e.getMessage(), e.getMessage().toLowerCase().contains("write why") ? txtNote : spnQuantity);
            return;
        } catch (SQLException e) {
            showError("Could not save the change:\n" + ErrorLog.describe(e));
            return;
        }
        if (onSaved != null) {
            onSaved.run();
        }
        dispose();
    }

    private void fail(String message, JComponent field) {
        showWarning(message);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
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

        grpType = new javax.swing.ButtonGroup();
        lblItem = new javax.swing.JLabel();
        rdoStockIn = new javax.swing.JRadioButton();
        rdoSold = new javax.swing.JRadioButton();
        rdoAdjust = new javax.swing.JRadioButton();
        lblQuantity = new javax.swing.JLabel();
        spnQuantity = new javax.swing.JSpinner();
        lblNote = new javax.swing.JLabel();
        txtNote = new javax.swing.JTextField();
        lblHint = new javax.swing.JLabel();
        btnCancel = new javax.swing.JButton();
        btnSave = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("MediX – Stock In / Out");
        setPreferredSize(new java.awt.Dimension(320, 380));
        setResizable(false);

        lblItem.setText("Medicine · batch");

        grpType.add(rdoStockIn);
        rdoStockIn.setSelected(true);
        rdoStockIn.setText("Stock in (units received)");
        rdoStockIn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                rdoStockInActionPerformed(evt);
            }
        });

        grpType.add(rdoSold);
        rdoSold.setText("Sold");
        rdoSold.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                rdoSoldActionPerformed(evt);
            }
        });

        grpType.add(rdoAdjust);
        rdoAdjust.setText("Adjust to the exact count");
        rdoAdjust.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                rdoAdjustActionPerformed(evt);
            }
        });

        lblQuantity.setText("Units received *");

        lblNote.setText("Note");

        lblHint.setText("After saving: -");

        btnCancel.setText("Cancel");
        btnCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelActionPerformed(evt);
            }
        });

        btnSave.setText("Save");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblItem, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(rdoStockIn)
                    .addComponent(rdoSold)
                    .addComponent(rdoAdjust)
                    .addComponent(lblQuantity)
                    .addComponent(spnQuantity, javax.swing.GroupLayout.DEFAULT_SIZE, 280, Short.MAX_VALUE)
                    .addComponent(lblNote)
                    .addComponent(txtNote, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblHint, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addComponent(btnCancel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnSave)))
                .addGap(20, 20, 20))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(lblItem)
                .addGap(10, 10, 10)
                .addComponent(rdoStockIn)
                .addComponent(rdoSold)
                .addComponent(rdoAdjust)
                .addGap(10, 10, 10)
                .addComponent(lblQuantity)
                .addGap(4, 4, 4)
                .addComponent(spnQuantity, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblNote)
                .addGap(4, 4, 4)
                .addComponent(txtNote, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(6, 6, 6)
                .addComponent(lblHint)
                .addGap(10, 10, Short.MAX_VALUE)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnCancel)
                    .addComponent(btnSave))
                .addGap(14, 14, 14))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void rdoStockInActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_rdoStockInActionPerformed
        updateMode();
    }//GEN-LAST:event_rdoStockInActionPerformed

    private void rdoSoldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_rdoSoldActionPerformed
        updateMode();
    }//GEN-LAST:event_rdoSoldActionPerformed

    private void rdoAdjustActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_rdoAdjustActionPerformed
        updateMode();
    }//GEN-LAST:event_rdoAdjustActionPerformed

    private void btnCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelActionPerformed
        dispose();
    }//GEN-LAST:event_btnCancelActionPerformed

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
        save();
    }//GEN-LAST:event_btnSaveActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCancel;
    private javax.swing.JButton btnSave;
    private javax.swing.ButtonGroup grpType;
    private javax.swing.JLabel lblHint;
    private javax.swing.JLabel lblItem;
    private javax.swing.JLabel lblNote;
    private javax.swing.JLabel lblQuantity;
    private javax.swing.JRadioButton rdoAdjust;
    private javax.swing.JRadioButton rdoSold;
    private javax.swing.JRadioButton rdoStockIn;
    private javax.swing.JSpinner spnQuantity;
    private javax.swing.JTextField txtNote;
    // End of variables declaration//GEN-END:variables
}
