package medix.forms;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.service.MedicineService;
import medix.service.ServiceException;
import medix.util.ErrorLog;
import medix.util.FormUtil;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * Admin: the medicine catalog. Select a row to edit or delete it; Clear to add a new one.
 */
public class MedicineCatalogFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Medicine Catalog";

    private final MedicineService medicineService = new MedicineService();
    private DefaultTableModel tableModel;
    private final Map<Integer, Medicine> shownById = new HashMap<Integer, Medicine>();
    /** The medicine in the form, or null when adding a new one. */
    private Medicine editing;

    public MedicineCatalogFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.ADMIN_CATALOG);
        cmbForm.setModel(new DefaultComboBoxModel<MedicineForm>(MedicineService.getForms()));
        setupTable();
        FormUtil.onTextChange(txtSearch, this::reload);
        reload();
        clearForm();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.styleCard(pnlForm);
        UITheme.styleHeading(lblFormTitle);
        UITheme.styleMuted(lblCount);
        chkRxRequired.setOpaque(false);
        UITheme.stylePrimaryButton(btnSave);
        UITheme.stylePrimaryButton(btnUpdate);
        UITheme.styleDangerButton(btnDelete);
        txtSearch.putClientProperty("JTextField.placeholderText", "Brand or generic name");
        txtBrand.putClientProperty("JTextField.placeholderText", "e.g. Napa");
        txtGeneric.putClientProperty("JTextField.placeholderText", "e.g. Paracetamol");
        txtStrength.putClientProperty("JTextField.placeholderText", "e.g. 500 mg");
        txtManufacturer.putClientProperty("JTextField.placeholderText", "e.g. Beximco Pharmaceuticals");
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblMedicines, "ID", "Brand", "Generic", "Strength", "Form", "Manufacturer", "Rx");
        TableUtil.setColumnWidth(tblMedicines, 1, 110);
        TableUtil.setColumnWidth(tblMedicines, 2, 180);
        TableUtil.setColumnWidth(tblMedicines, 3, 110);
        TableUtil.setColumnWidth(tblMedicines, 4, 90);
        TableUtil.setColumnWidth(tblMedicines, 5, 170);
        TableUtil.setColumnWidth(tblMedicines, 6, 45);
        TableUtil.centerColumn(tblMedicines, 6);
        tblMedicines.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Integer id = TableUtil.getSelectedId(tblMedicines);
                if (id != null) {
                    fillForm(shownById.get(id));
                }
            }
        });
    }

    private void reload() {
        Integer keep = editing == null ? null : editing.getId();
        List<Medicine> list;
        try {
            list = medicineService.search(txtSearch.getText());
        } catch (SQLException e) {
            showError("Could not load the medicines:\n" + ErrorLog.describe(e));
            return;
        }
        shownById.clear();
        TableUtil.clear(tableModel);
        for (Medicine m : list) {
            shownById.put(m.getId(), m);
            tableModel.addRow(new Object[]{m.getId(), m.getBrandName(), m.getGenericName(), m.getStrength(),
                m.getForm(), m.getManufacturer() == null ? "" : m.getManufacturer(), m.isRxRequired() ? "Yes" : "No"});
        }
        lblCount.setText(list.size() + (list.size() == 1 ? " medicine" : " medicines")
                + (ValidationUtil.isBlank(txtSearch.getText()) ? "" : " found"));
        if (keep != null) {
            TableUtil.selectId(tblMedicines, keep);
        }
    }

    private void fillForm(Medicine m) {
        if (m == null) {
            return;
        }
        editing = m;
        txtBrand.setText(m.getBrandName());
        txtGeneric.setText(m.getGenericName());
        txtStrength.setText(m.getStrength());
        cmbForm.setSelectedItem(m.getForm());
        txtManufacturer.setText(m.getManufacturer() == null ? "" : m.getManufacturer());
        chkRxRequired.setSelected(m.isRxRequired());
        lblFormTitle.setText("Edit medicine #" + m.getId());
        updateButtons();
    }

    private void clearForm() {
        editing = null;
        tblMedicines.clearSelection();
        txtBrand.setText("");
        txtGeneric.setText("");
        txtStrength.setText("");
        cmbForm.setSelectedItem(MedicineForm.TABLET);
        txtManufacturer.setText("");
        chkRxRequired.setSelected(false);
        lblFormTitle.setText("Add a new medicine");
        updateButtons();
        SwingUtilities.invokeLater(txtBrand::requestFocusInWindow);
    }

    private void updateButtons() {
        btnSave.setEnabled(editing == null);
        btnUpdate.setEnabled(editing != null);
        btnDelete.setEnabled(editing != null);
    }

    /** Reads the form into a Medicine; returns null (after showing the problem) if a field is missing. */
    private Medicine readForm() {
        if (ValidationUtil.isBlank(txtBrand.getText())) {
            return fail("Enter the brand name.", txtBrand);
        }
        if (ValidationUtil.isBlank(txtGeneric.getText())) {
            return fail("Enter the generic name.", txtGeneric);
        }
        if (ValidationUtil.isBlank(txtStrength.getText())) {
            return fail("Enter the strength, e.g. 500 mg or 120 mg/5 ml.", txtStrength);
        }
        if (cmbForm.getSelectedItem() == null) {
            return fail("Choose the form.", cmbForm);
        }
        Medicine m = new Medicine();
        m.setBrandName(txtBrand.getText());
        m.setGenericName(txtGeneric.getText());
        m.setStrength(txtStrength.getText());
        m.setForm((MedicineForm) cmbForm.getSelectedItem());
        m.setManufacturer(txtManufacturer.getText());
        m.setRxRequired(chkRxRequired.isSelected());
        return m;
    }

    private void save() {
        Medicine m = readForm();
        if (m == null) {
            return;
        }
        try {
            int id = medicineService.add(m);
            showInfo(MedicineService.label(m) + " was added to the catalog.");
            txtSearch.setText("");
            clearForm();
            reload();
            TableUtil.selectId(tblMedicines, id);
        } catch (ServiceException e) {
            fail(e.getMessage(), txtBrand);
        } catch (SQLException e) {
            showError("Could not save the medicine:\n" + ErrorLog.describe(e));
        }
    }

    private void update() {
        Medicine m = readForm();
        if (m == null) {
            return;
        }
        m.setId(editing.getId());
        m.setCreatedAt(editing.getCreatedAt());
        try {
            medicineService.update(m);
            editing = m;
            reload();
            showInfo(MedicineService.label(m) + " was updated.");
        } catch (ServiceException e) {
            fail(e.getMessage(), txtBrand);
        } catch (SQLException e) {
            showError("Could not update the medicine:\n" + ErrorLog.describe(e));
        }
    }

    private void delete() {
        String name = MedicineService.label(editing);
        if (JOptionPane.showConfirmDialog(this, "Delete " + name + " from the catalog?", TITLE,
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            medicineService.delete(editing.getId());
            clearForm();
            reload();
            showInfo(name + " was deleted.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not delete the medicine:\n" + ErrorLog.describe(e));
        }
    }

    private Medicine fail(String message, JComponent field) {
        showWarning(message);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
        return null;
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
        pnlForm = new javax.swing.JPanel();
        lblFormTitle = new javax.swing.JLabel();
        lblBrand = new javax.swing.JLabel();
        txtBrand = new javax.swing.JTextField();
        lblGeneric = new javax.swing.JLabel();
        txtGeneric = new javax.swing.JTextField();
        lblStrength = new javax.swing.JLabel();
        txtStrength = new javax.swing.JTextField();
        lblForm = new javax.swing.JLabel();
        cmbForm = new javax.swing.JComboBox<>();
        lblManufacturer = new javax.swing.JLabel();
        txtManufacturer = new javax.swing.JTextField();
        chkRxRequired = new javax.swing.JCheckBox();
        btnSave = new javax.swing.JButton();
        btnUpdate = new javax.swing.JButton();
        btnDelete = new javax.swing.JButton();
        btnClear = new javax.swing.JButton();
        lblSearch = new javax.swing.JLabel();
        txtSearch = new javax.swing.JTextField();
        scrMedicines = new javax.swing.JScrollPane();
        tblMedicines = new javax.swing.JTable();
        lblCount = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Medicine Catalog");
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

        lblTitle.setText("Medicine Catalog");

        lblSubtitle.setText("Medicines that pharmacies can add to their inventory.");

        lblFormTitle.setText("Add or edit a medicine");

        lblBrand.setText("Brand name *");

        lblGeneric.setText("Generic name *");

        lblStrength.setText("Strength *");

        lblForm.setText("Form *");

        lblManufacturer.setText("Manufacturer");

        chkRxRequired.setText("Prescription required (Rx)");

        btnSave.setText("Save");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        btnUpdate.setText("Update");
        btnUpdate.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnUpdateActionPerformed(evt);
            }
        });

        btnDelete.setText("Delete");
        btnDelete.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnDeleteActionPerformed(evt);
            }
        });

        btnClear.setText("Clear");
        btnClear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnClearActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlFormLayout = new javax.swing.GroupLayout(pnlForm);
        pnlForm.setLayout(pnlFormLayout);
        pnlFormLayout.setHorizontalGroup(
            pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlFormLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblFormTitle)
                    .addComponent(lblBrand)
                    .addComponent(txtBrand, javax.swing.GroupLayout.DEFAULT_SIZE, 260, Short.MAX_VALUE)
                    .addComponent(lblGeneric)
                    .addComponent(txtGeneric, javax.swing.GroupLayout.DEFAULT_SIZE, 260, Short.MAX_VALUE)
                    .addComponent(lblStrength)
                    .addComponent(txtStrength, javax.swing.GroupLayout.DEFAULT_SIZE, 260, Short.MAX_VALUE)
                    .addComponent(lblForm)
                    .addComponent(cmbForm, javax.swing.GroupLayout.DEFAULT_SIZE, 260, Short.MAX_VALUE)
                    .addComponent(lblManufacturer)
                    .addComponent(txtManufacturer, javax.swing.GroupLayout.DEFAULT_SIZE, 260, Short.MAX_VALUE)
                    .addComponent(chkRxRequired)
                    .addGroup(pnlFormLayout.createSequentialGroup()
                        .addComponent(btnSave, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnUpdate, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(pnlFormLayout.createSequentialGroup()
                        .addComponent(btnDelete, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnClear, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                .addGap(16, 16, 16))
        );
        pnlFormLayout.setVerticalGroup(
            pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlFormLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(lblFormTitle)
                .addGap(14, 14, 14)
                .addComponent(lblBrand)
                .addGap(4, 4, 4)
                .addComponent(txtBrand, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(lblGeneric)
                .addGap(4, 4, 4)
                .addComponent(txtGeneric, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(lblStrength)
                .addGap(4, 4, 4)
                .addComponent(txtStrength, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(lblForm)
                .addGap(4, 4, 4)
                .addComponent(cmbForm, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(lblManufacturer)
                .addGap(4, 4, 4)
                .addComponent(txtManufacturer, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(12, 12, 12)
                .addComponent(chkRxRequired)
                .addGap(18, 18, 18)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnSave)
                    .addComponent(btnUpdate))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnDelete)
                    .addComponent(btnClear))
                .addGap(16, 16, Short.MAX_VALUE))
        );

        lblSearch.setText("Search");

        scrMedicines.setViewportView(tblMedicines);

        lblCount.setText("0 medicines");

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
                        .addComponent(pnlForm, javax.swing.GroupLayout.PREFERRED_SIZE, 300, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(20, 20, 20)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(lblSearch)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtSearch, javax.swing.GroupLayout.DEFAULT_SIZE, 300, Short.MAX_VALUE))
                            .addComponent(scrMedicines, javax.swing.GroupLayout.DEFAULT_SIZE, 640, Short.MAX_VALUE)
                            .addComponent(lblCount))))
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
                    .addComponent(pnlForm, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(lblSearch)
                            .addComponent(txtSearch, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(10, 10, 10)
                        .addComponent(scrMedicines, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGap(8, 8, 8)
                        .addComponent(lblCount)))
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
        save();
    }//GEN-LAST:event_btnSaveActionPerformed

    private void btnUpdateActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUpdateActionPerformed
        update();
    }//GEN-LAST:event_btnUpdateActionPerformed

    private void btnDeleteActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDeleteActionPerformed
        delete();
    }//GEN-LAST:event_btnDeleteActionPerformed

    private void btnClearActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearActionPerformed
        clearForm();
    }//GEN-LAST:event_btnClearActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnClear;
    private javax.swing.JButton btnDelete;
    private javax.swing.JButton btnSave;
    private javax.swing.JButton btnUpdate;
    private javax.swing.JCheckBox chkRxRequired;
    private javax.swing.JComboBox<medix.model.MedicineForm> cmbForm;
    private javax.swing.JLabel lblBrand;
    private javax.swing.JLabel lblCount;
    private javax.swing.JLabel lblForm;
    private javax.swing.JLabel lblFormTitle;
    private javax.swing.JLabel lblGeneric;
    private javax.swing.JLabel lblManufacturer;
    private javax.swing.JLabel lblSearch;
    private javax.swing.JLabel lblStrength;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlForm;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrMedicines;
    private javax.swing.JTable tblMedicines;
    private javax.swing.JTextField txtBrand;
    private javax.swing.JTextField txtGeneric;
    private javax.swing.JTextField txtManufacturer;
    private javax.swing.JTextField txtSearch;
    private javax.swing.JTextField txtStrength;
    // End of variables declaration//GEN-END:variables
}
