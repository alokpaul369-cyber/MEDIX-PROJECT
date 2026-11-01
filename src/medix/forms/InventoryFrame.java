package medix.forms;

import java.awt.Color;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import medix.model.Inventory;
import medix.model.InventoryItem;
import medix.model.Medicine;
import medix.model.PharmacyDetails;
import medix.service.InventoryService;
import medix.service.PharmacyService;
import medix.service.ServiceException;
import medix.util.AutoComplete;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.FormUtil;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * Pharmacy home: the stock of the logged-in pharmacy. Add batches on the left; click a row
 * to edit it or to open Stock in / out. Rows: low stock yellow, expiring within 60 days
 * orange, expired red.
 */
public class InventoryFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Inventory";
    private static final String[] FILTERS = {"All stock", "Low stock", "Expiring soon", "Expired", "Out of stock"};

    private final InventoryService inventoryService = new InventoryService();
    private final PharmacyService pharmacyService = new PharmacyService();
    private DefaultTableModel tableModel;
    private AutoComplete<Medicine> medicineAutoComplete;
    /** Every batch of the pharmacy. */
    private List<InventoryItem> allItems = new ArrayList<InventoryItem>();
    /** The batches shown in the table, in table-model order. */
    private List<InventoryItem> rows = new ArrayList<InventoryItem>();
    /** Medicine picked in the autocomplete (null until one is picked). */
    private Medicine selectedMedicine;
    /** The batch loaded into the form, or null when adding a new batch. */
    private InventoryItem editing;
    private StockDialogFrame stockDialog;

    /** Opens the inventory with one batch selected and loaded into the form (e.g. from an alert). */
    public InventoryFrame(int inventoryId) {
        this();
        TableUtil.selectId(tblInventory, inventoryId);
    }

    public InventoryFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.PHARMACY_INVENTORY);
        setupForm();
        setupTable();
        reload();
        clearForm();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.styleCard(pnlForm);
        UITheme.styleHeading(lblFormTitle);
        UITheme.stylePrimaryButton(btnAdd);
        UITheme.stylePrimaryButton(btnUpdate);
        btnStockInOut.setFont(UITheme.FONT_BODY_BOLD);
        txtSearch.putClientProperty("JTextField.placeholderText", "Medicine or batch");
        txtMedicine.putClientProperty("JTextField.placeholderText", "Type a brand or generic name");
        txtBatchNo.putClientProperty("JTextField.placeholderText", "e.g. B-2405");
        txtPrice.putClientProperty("JTextField.placeholderText", "e.g. 1.20");
        btnConfirmAll.setToolTipText("Marks every batch as checked now without changing quantities.");
    }

    private void setupForm() {
        spnQuantity.setModel(new SpinnerNumberModel(1, 1, InventoryService.MAX_UNITS, 1));
        spnLowStock.setModel(new SpinnerNumberModel(10, 0, 100000, 1));
        medicineAutoComplete = AutoComplete.attach(txtMedicine, inventoryService::suggestMedicines,
                InventoryService::suggestionLabel, m -> selectedMedicine = m);
        cmbFilter.setModel(new DefaultComboBoxModel<String>(FILTERS));
        FormUtil.onTextChange(txtSearch, this::applyFilter);
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblInventory,
                "ID", "Medicine", "Batch", "Qty", "Reserved", "Available", "Price", "Expiry", "Updated");
        TableUtil.setColumnWidth(tblInventory, 1, 150);
        TableUtil.setColumnWidth(tblInventory, 2, 65);
        int[] numberWidths = {45, 72, 72};
        for (int col = 3; col <= 5; col++) {
            TableUtil.setColumnWidth(tblInventory, col, numberWidths[col - 3]);
            TableUtil.centerColumn(tblInventory, col);
        }
        TableUtil.setColumnWidth(tblInventory, 6, 60);
        TableUtil.setColumnWidth(tblInventory, 7, 100);
        TableUtil.setColumnWidth(tblInventory, 8, 66);
        TableUtil.centerColumn(tblInventory, 8);
        TableUtil.setRowBackground(tblInventory, modelRow -> rowColor(rows.get(modelRow)));
        tblInventory.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Integer id = TableUtil.getSelectedId(tblInventory);
                if (id != null) {
                    loadIntoForm(find(id));
                }
            }
        });
    }

    /** Expired red, expiring soon orange, low stock yellow. */
    private static Color rowColor(InventoryItem item) {
        if (item.isExpired()) {
            return UITheme.ROW_EXPIRED;
        }
        if (item.isExpiringSoon()) {
            return UITheme.ROW_EXPIRING;
        }
        if (item.isLowStock()) {
            return UITheme.ROW_LOW_STOCK;
        }
        return null;
    }

    // ---------------------------------------------------------------- loading

    private void reload() {
        try {
            allItems = inventoryService.getItems();
            PharmacyDetails me = pharmacyService.getMyPharmacy();
            lblTitle.setText("Inventory · " + me.getPharmacy().getName());
            String last = InventoryService.lastUpdated(allItems);
            String ago = DateUtil.ago(last);
            lblSubtitle.setText((me.getAreaName().isEmpty() ? "No area" : me.getAreaName())
                    + "  ·  Trust score " + me.getPharmacy().getTrustScore()
                    + "  ·  " + (last == null ? "No stock yet"
                            : "Stock last updated " + ("now".equals(ago) ? "just now" : ago + " ago")));
        } catch (ServiceException e) {
            allItems = new ArrayList<InventoryItem>();
            showWarning(e.getMessage());
        } catch (SQLException e) {
            allItems = new ArrayList<InventoryItem>();
            showError("Could not load the stock:\n" + ErrorLog.describe(e));
        }
        applyFilter();
        updateFooter();
    }

    private void applyFilter() {
        Integer keep = editing == null ? null : editing.getInventory().getId();
        String text = txtSearch.getText().trim().toLowerCase();
        int filter = Math.max(0, cmbFilter.getSelectedIndex());
        rows = new ArrayList<InventoryItem>();
        for (InventoryItem it : allItems) {
            String haystack = (InventoryService.suggestionLabel(it.getMedicine()) + " " + it.getInventory().getBatchNo()).toLowerCase();
            if (!text.isEmpty() && !haystack.contains(text)) {
                continue;
            }
            boolean show = filter == 0
                    || (filter == 1 && it.isLowStock())
                    || (filter == 2 && it.isExpiringSoon())
                    || (filter == 3 && it.isExpired())
                    || (filter == 4 && it.isOutOfStock());
            if (show) {
                rows.add(it);
            }
        }
        TableUtil.clear(tableModel);
        for (InventoryItem it : rows) {
            Inventory inv = it.getInventory();
            String expiry = DateUtil.toDisplayDate(inv.getExpiryDate());
            tableModel.addRow(new Object[]{inv.getId(), it.getMedicineLabel(), inv.getBatchNo(), inv.getQuantity(),
                inv.getReservedQty(), it.getAvailable(), String.format(Locale.US, "%.2f", inv.getPrice()),
                it.isExpired() ? "<html>" + UITheme.crossHtml() + " " + expiry + "</html>" : expiry, DateUtil.ago(inv.getUpdatedAt())});
        }
        if (keep != null && !TableUtil.selectId(tblInventory, keep)) {
            tblInventory.clearSelection();
        }
    }

    private void updateFooter() {
        int low = 0;
        int expiring = 0;
        int expired = 0;
        double value = 0;
        for (InventoryItem it : allItems) {
            if (it.isExpired()) {
                expired++;
            } else {
                value += it.getStockValue();
                if (it.isExpiringSoon()) {
                    expiring++;
                }
            }
            if (it.isLowStock()) {
                low++;
            }
        }
        lblFooter.setText("<html>" + chip(UITheme.ROW_LOW_STOCK, "Low stock: " + low) + "&nbsp;&nbsp;"
                + chip(UITheme.ROW_EXPIRING, "Expiring in 60 days: " + expiring) + "&nbsp;&nbsp;"
                + chip(UITheme.ROW_EXPIRED, "Expired: " + expired)
                + "&nbsp;&nbsp;&nbsp;&nbsp;Stock value: <b>Tk " + String.format(Locale.US, "%,.2f", value)
                + "</b> (not expired)&nbsp;&nbsp;·&nbsp;&nbsp;" + allItems.size() + " batch(es)</html>");
    }

    private static String chip(Color bg, String text) {
        return "<span style='background-color:" + String.format("#%02X%02X%02X", bg.getRed(), bg.getGreen(), bg.getBlue())
                + "'>&nbsp;" + text + "&nbsp;</span>";
    }

    private InventoryItem find(int inventoryId) {
        for (InventoryItem it : allItems) {
            if (it.getInventory().getId() == inventoryId) {
                return it;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- form

    private void loadIntoForm(InventoryItem item) {
        if (item == null) {
            return;
        }
        editing = item;
        Inventory inv = item.getInventory();
        selectedMedicine = item.getMedicine();
        medicineAutoComplete.setTextSilently(InventoryService.suggestionLabel(item.getMedicine()));
        txtMedicine.setEnabled(false);
        txtBatchNo.setText(inv.getBatchNo());
        spnQuantity.setModel(new SpinnerNumberModel(inv.getQuantity(), 0, Math.max(inv.getQuantity(), InventoryService.MAX_UNITS), 1));
        spnQuantity.setEnabled(false);
        spnQuantity.setToolTipText("Use Stock in / out to change the quantity.");
        txtPrice.setText(String.format(Locale.US, "%.2f", inv.getPrice()));
        dateExpiry.setDate(DateUtil.parseDate(inv.getExpiryDate()));
        spnLowStock.setValue(inv.getLowStockThreshold());
        lblFormTitle.setText("Edit batch #" + inv.getId());
        updateButtons();
    }

    private void clearForm() {
        editing = null;
        tblInventory.clearSelection();
        txtMedicine.setEnabled(true);
        medicineAutoComplete.setTextSilently("");
        selectedMedicine = null;
        txtBatchNo.setText("");
        spnQuantity.setModel(new SpinnerNumberModel(1, 1, InventoryService.MAX_UNITS, 1));
        spnQuantity.setEnabled(true);
        spnQuantity.setToolTipText(null);
        txtPrice.setText("");
        dateExpiry.setDate(null);
        spnLowStock.setValue(10);
        lblFormTitle.setText("Add batch");
        updateButtons();
    }

    private void updateButtons() {
        btnAdd.setEnabled(editing == null);
        btnUpdate.setEnabled(editing != null);
        btnStockInOut.setEnabled(editing != null);
        btnConfirmAll.setEnabled(!allItems.isEmpty());
    }

    /** Checks the batch fields shared by Add and Update; shows the first problem. */
    private boolean checkBatchFields() {
        if (ValidationUtil.isBlank(txtBatchNo.getText())) {
            return fail("Enter the batch number printed on the box.", txtBatchNo);
        }
        Double price = ValidationUtil.parseDouble(txtPrice.getText());
        if (price == null || price <= 0) {
            return fail("Enter a price per unit in Tk that is more than 0, e.g. 1.20.", txtPrice);
        }
        if (dateExpiry.getDate() == null) {
            return fail("Choose the expiry date.", dateExpiry.getDateEditor().getUiComponent());
        }
        return true;
    }

    private void addBatch() {
        if (selectedMedicine == null) {
            fail("Choose a medicine from the list (type a few letters of the name).", txtMedicine);
            return;
        }
        if (!checkBatchFields()) {
            return;
        }
        try {
            int id = inventoryService.addBatch(selectedMedicine.getId(), txtBatchNo.getText(),
                    FormUtil.intValue(spnQuantity), ValidationUtil.parseDouble(txtPrice.getText()),
                    dateExpiry.getDate(), FormUtil.intValue(spnLowStock));
            showInfo("Batch " + txtBatchNo.getText().trim() + " of " + selectedMedicine.getBrandName()
                    + " was added (stock #" + id + ").");
            // stay in "Add batch" mode so the next batch can be typed straight away
            clearForm();
            reload();
        } catch (ServiceException e) {
            fail(e.getMessage(), fieldFor(e.getMessage()));
        } catch (SQLException e) {
            showError("Could not add the batch:\n" + ErrorLog.describe(e));
        }
    }

    private void updateBatch() {
        if (!checkBatchFields()) {
            return;
        }
        int id = editing.getInventory().getId();
        try {
            inventoryService.updateBatch(id, txtBatchNo.getText(), ValidationUtil.parseDouble(txtPrice.getText()),
                    dateExpiry.getDate(), FormUtil.intValue(spnLowStock));
            reload();
            loadIntoForm(find(id));
            showInfo("Batch " + editing.getInventory().getBatchNo() + " was updated.");
        } catch (ServiceException e) {
            fail(e.getMessage(), fieldFor(e.getMessage()));
        } catch (SQLException e) {
            showError("Could not update the batch:\n" + ErrorLog.describe(e));
        }
    }

    /** The field that a service message is about, for the cursor. */
    private JComponent fieldFor(String message) {
        String m = message.toLowerCase();
        if (m.contains("batch")) {
            return txtBatchNo;
        }
        if (m.contains("price")) {
            return txtPrice;
        }
        if (m.contains("expiry")) {
            return dateExpiry.getDateEditor().getUiComponent();
        }
        if (m.contains("quantity")) {
            return spnQuantity;
        }
        return txtMedicine;
    }

    private void openStockDialog() {
        if (stockDialog != null && stockDialog.isDisplayable()) {
            stockDialog.toFront();
            return;
        }
        final int id = editing.getInventory().getId();
        stockDialog = new StockDialogFrame(id, () -> {
            reload();
            loadIntoForm(find(id));
        });
        stockDialog.setVisible(true);
    }

    private void confirmAll() {
        if (JOptionPane.showConfirmDialog(this, "Is the quantity of every batch correct right now?\n"
                + "This marks all " + allItems.size() + " batch(es) as checked without changing quantities.",
                TITLE, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            int rowsConfirmed = inventoryService.confirmAllStock();
            reload();
            showInfo(rowsConfirmed + " batch(es) marked as checked just now.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not confirm the stock:\n" + ErrorLog.describe(e));
        }
    }

    private boolean fail(String message, JComponent field) {
        showWarning(message);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
        return false;
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
        lblSearch = new javax.swing.JLabel();
        txtSearch = new javax.swing.JTextField();
        lblFilter = new javax.swing.JLabel();
        cmbFilter = new javax.swing.JComboBox<>();
        pnlForm = new javax.swing.JPanel();
        lblFormTitle = new javax.swing.JLabel();
        lblMedicine = new javax.swing.JLabel();
        txtMedicine = new javax.swing.JTextField();
        lblBatchNo = new javax.swing.JLabel();
        txtBatchNo = new javax.swing.JTextField();
        lblQuantity = new javax.swing.JLabel();
        spnQuantity = new javax.swing.JSpinner();
        lblPrice = new javax.swing.JLabel();
        txtPrice = new javax.swing.JTextField();
        lblExpiry = new javax.swing.JLabel();
        dateExpiry = new com.toedter.calendar.JDateChooser();
        lblLowStock = new javax.swing.JLabel();
        spnLowStock = new javax.swing.JSpinner();
        btnAdd = new javax.swing.JButton();
        btnUpdate = new javax.swing.JButton();
        btnClear = new javax.swing.JButton();
        btnStockInOut = new javax.swing.JButton();
        btnConfirmAll = new javax.swing.JButton();
        scrInventory = new javax.swing.JScrollPane();
        tblInventory = new javax.swing.JTable();
        lblFooter = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Inventory");
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

        lblTitle.setText("Inventory");

        lblSubtitle.setText("Area · Trust score · Stock last updated");

        lblSearch.setText("Search");

        lblFilter.setText("Show");

        cmbFilter.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbFilterActionPerformed(evt);
            }
        });

        lblFormTitle.setText("Add batch");

        lblMedicine.setText("Medicine *");

        lblBatchNo.setText("Batch no. *");

        lblQuantity.setText("Quantity *");

        lblPrice.setText("Price (Tk) *");

        lblExpiry.setText("Expiry date *");

        dateExpiry.setDateFormatString("yyyy-MM-dd");

        lblLowStock.setText("Low-stock alert at");

        btnAdd.setText("Add");
        btnAdd.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnAddActionPerformed(evt);
            }
        });

        btnUpdate.setText("Update");
        btnUpdate.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnUpdateActionPerformed(evt);
            }
        });

        btnClear.setText("Clear");
        btnClear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnClearActionPerformed(evt);
            }
        });

        btnStockInOut.setText("Stock in / out...");
        btnStockInOut.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnStockInOutActionPerformed(evt);
            }
        });

        btnConfirmAll.setText("Confirm all stock is correct");
        btnConfirmAll.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnConfirmAllActionPerformed(evt);
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
                    .addComponent(lblMedicine)
                    .addComponent(txtMedicine, javax.swing.GroupLayout.DEFAULT_SIZE, 248, Short.MAX_VALUE)
                    .addComponent(lblBatchNo)
                    .addComponent(txtBatchNo, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(pnlFormLayout.createSequentialGroup()
                        .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblQuantity)
                            .addComponent(spnQuantity, javax.swing.GroupLayout.DEFAULT_SIZE, 118, Short.MAX_VALUE))
                        .addGap(12, 12, 12)
                        .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblPrice)
                            .addComponent(txtPrice, javax.swing.GroupLayout.DEFAULT_SIZE, 118, Short.MAX_VALUE)))
                    .addGroup(pnlFormLayout.createSequentialGroup()
                        .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblExpiry)
                            .addComponent(dateExpiry, javax.swing.GroupLayout.DEFAULT_SIZE, 118, Short.MAX_VALUE))
                        .addGap(12, 12, 12)
                        .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblLowStock)
                            .addComponent(spnLowStock, javax.swing.GroupLayout.DEFAULT_SIZE, 118, Short.MAX_VALUE)))
                    .addGroup(pnlFormLayout.createSequentialGroup()
                        .addComponent(btnAdd, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnUpdate, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnClear, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addComponent(btnStockInOut, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnConfirmAll, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(16, 16, 16))
        );
        pnlFormLayout.setVerticalGroup(
            pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlFormLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(lblFormTitle)
                .addGap(14, 14, 14)
                .addComponent(lblMedicine)
                .addGap(4, 4, 4)
                .addComponent(txtMedicine, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(lblBatchNo)
                .addGap(4, 4, 4)
                .addComponent(txtBatchNo, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblQuantity)
                    .addComponent(lblPrice))
                .addGap(4, 4, 4)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(spnQuantity, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtPrice, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblExpiry)
                    .addComponent(lblLowStock))
                .addGap(4, 4, 4)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(dateExpiry, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(spnLowStock, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(18, 18, 18)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnAdd)
                    .addComponent(btnUpdate)
                    .addComponent(btnClear))
                .addGap(24, 24, 24)
                .addComponent(btnStockInOut)
                .addGap(8, 8, 8)
                .addComponent(btnConfirmAll)
                .addGap(16, 16, Short.MAX_VALUE))
        );

        scrInventory.setViewportView(tblInventory);

        lblFooter.setText("Low stock: 0 · Expiring: 0 · Expired: 0 · Stock value: Tk 0.00");

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
                        .addComponent(pnlForm, javax.swing.GroupLayout.PREFERRED_SIZE, 280, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(16, 16, 16)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(lblSearch)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtSearch, javax.swing.GroupLayout.DEFAULT_SIZE, 260, Short.MAX_VALUE)
                                .addGap(16, 16, 16)
                                .addComponent(lblFilter)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(cmbFilter, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addComponent(scrInventory, javax.swing.GroupLayout.DEFAULT_SIZE, 686, Short.MAX_VALUE)
                            .addComponent(lblFooter, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
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
                            .addComponent(txtSearch, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(lblFilter)
                            .addComponent(cmbFilter, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(10, 10, 10)
                        .addComponent(scrInventory, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGap(8, 8, 8)
                        .addComponent(lblFooter)))
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void cmbFilterActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbFilterActionPerformed
        applyFilter();
    }//GEN-LAST:event_cmbFilterActionPerformed

    private void btnAddActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnAddActionPerformed
        addBatch();
    }//GEN-LAST:event_btnAddActionPerformed

    private void btnUpdateActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUpdateActionPerformed
        updateBatch();
    }//GEN-LAST:event_btnUpdateActionPerformed

    private void btnClearActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearActionPerformed
        clearForm();
    }//GEN-LAST:event_btnClearActionPerformed

    private void btnStockInOutActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnStockInOutActionPerformed
        openStockDialog();
    }//GEN-LAST:event_btnStockInOutActionPerformed

    private void btnConfirmAllActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnConfirmAllActionPerformed
        confirmAll();
    }//GEN-LAST:event_btnConfirmAllActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnAdd;
    private javax.swing.JButton btnClear;
    private javax.swing.JButton btnConfirmAll;
    private javax.swing.JButton btnStockInOut;
    private javax.swing.JButton btnUpdate;
    private javax.swing.JComboBox<String> cmbFilter;
    private com.toedter.calendar.JDateChooser dateExpiry;
    private javax.swing.JLabel lblBatchNo;
    private javax.swing.JLabel lblExpiry;
    private javax.swing.JLabel lblFilter;
    private javax.swing.JLabel lblFooter;
    private javax.swing.JLabel lblFormTitle;
    private javax.swing.JLabel lblLowStock;
    private javax.swing.JLabel lblMedicine;
    private javax.swing.JLabel lblPrice;
    private javax.swing.JLabel lblQuantity;
    private javax.swing.JLabel lblSearch;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlForm;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrInventory;
    private javax.swing.JSpinner spnLowStock;
    private javax.swing.JSpinner spnQuantity;
    private javax.swing.JTable tblInventory;
    private javax.swing.JTextField txtBatchNo;
    private javax.swing.JTextField txtMedicine;
    private javax.swing.JTextField txtPrice;
    private javax.swing.JTextField txtSearch;
    // End of variables declaration//GEN-END:variables
}
