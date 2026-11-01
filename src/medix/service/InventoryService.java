package medix.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import medix.dao.InventoryDao;
import medix.engine.AvailabilityCache;
import medix.dao.MedicineDao;
import medix.dao.PharmacyDao;
import medix.dao.StockMovementDao;
import medix.model.Inventory;
import medix.model.InventoryItem;
import medix.model.Medicine;
import medix.model.MovementReason;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.Role;
import medix.model.StockMovement;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * Stock of the logged-in pharmacy.
 *
 * Rules: only a VERIFIED pharmacy may change stock; every change runs in one transaction
 * that updates the batch (updated_at = now) and writes a stock_movements row
 * (ADD / SALE / ADJUST, with the user id and an optional note), then an audit_logs row.
 * When a change leaves available &lt;= low_stock_threshold the owner gets one LOW_STOCK
 * notification per batch per day.
 */
public class InventoryService {

    public static final int MAX_BATCH_LENGTH = 40;
    public static final int MAX_UNITS = 1000000;
    public static final String MSG_DUPLICATE_BATCH = "This batch already exists. Use Stock in to add units.";

    private final InventoryDao inventoryDao = new InventoryDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final MedicineDao medicineDao = new MedicineDao();
    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final AuditService auditService = new AuditService();

    // ---------------------------------------------------------------- reading

    public List<InventoryItem> getItems() throws ServiceException, SQLException {
        return inventoryDao.findItemsByPharmacy(myPharmacy().getId());
    }

    public InventoryItem getItem(int inventoryId) throws ServiceException, SQLException {
        InventoryItem item = inventoryDao.findItem(inventoryId, myPharmacy().getId());
        if (item == null) {
            throw new ServiceException("This batch no longer exists.");
        }
        return item;
    }

    /** Up to 8 catalog medicines for the autocomplete under the medicine field. */
    public List<Medicine> suggestMedicines(String text) throws SQLException {
        return medicineDao.suggest(text, 8);
    }

    /** e.g. "Napa 500 mg Tablet – Paracetamol" (autocomplete row). */
    public static String suggestionLabel(Medicine m) {
        return MedicineService.suggestionLabel(m);
    }

    /** Newest updated_at of the batches, or null when there is no stock. */
    public static String lastUpdated(List<InventoryItem> items) {
        String newest = null;
        for (InventoryItem it : items) {
            String u = it.getInventory().getUpdatedAt();
            if (u != null && (newest == null || u.compareTo(newest) > 0)) {
                newest = u;
            }
        }
        return newest;
    }

    // ---------------------------------------------------------------- changes

    /** Adds a new batch; returns its inventory id. */
    public int addBatch(int medicineId, String batchNo, int quantity, double price, Date expiry, int lowStock)
            throws ServiceException, SQLException {
        Pharmacy pharmacy = requireVerifiedPharmacy();
        if (medicineDao.findById(medicineId) == null) {
            throw new ServiceException("Choose a medicine from the catalog list.");
        }
        String batch = checkBatchNo(batchNo);
        if (quantity < 1 || quantity > MAX_UNITS) {
            throw new ServiceException("The quantity must be at least 1.");
        }
        checkPriceAndThreshold(price, lowStock);
        String expiryText = DateUtil.formatDate(expiry);
        if (expiry == null || DateUtil.daysUntil(expiryText) <= 0) {
            throw new ServiceException("The expiry date of a new batch must be in the future.");
        }
        if (inventoryDao.findBatch(pharmacy.getId(), medicineId, batch, 0) != null) {
            throw new ServiceException(MSG_DUPLICATE_BATCH);
        }
        Inventory inv = new Inventory();
        inv.setPharmacyId(pharmacy.getId());
        inv.setMedicineId(medicineId);
        inv.setBatchNo(batch);
        inv.setQuantity(quantity);
        inv.setReservedQty(0);
        inv.setPrice(price);
        inv.setExpiryDate(expiryText);
        inv.setLowStockThreshold(lowStock);
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                inventoryDao.insert(con, inv);
                movementDao.insert(con, movement(inv.getId(), quantity, MovementReason.ADD, "New batch"));
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        InventoryItem item = inventoryDao.findItem(inv.getId(), pharmacy.getId());
        auditService.log("STOCK_BATCH_ADDED", "inventory", inv.getId(),
                item.getMedicineLabel() + ", batch " + batch + ", qty " + quantity + ", Tk " + price);
        AvailabilityCache.invalidateMedicine(medicineId);
        checkLowStock(item);
        return inv.getId();
    }

    /** Changes batch number, price, expiry and threshold. The quantity is changed with stockIn/sell/adjust. */
    public void updateBatch(int inventoryId, String batchNo, double price, Date expiry, int lowStock)
            throws ServiceException, SQLException {
        Pharmacy pharmacy = requireVerifiedPharmacy();
        InventoryItem before = getItem(inventoryId);
        String batch = checkBatchNo(batchNo);
        checkPriceAndThreshold(price, lowStock);
        if (expiry == null) {
            throw new ServiceException("Choose the expiry date.");
        }
        String expiryText = DateUtil.formatDate(expiry);
        if (inventoryDao.findBatch(pharmacy.getId(), before.getInventory().getMedicineId(), batch, inventoryId) != null) {
            throw new ServiceException("Another batch of this medicine already has the number " + batch + ".");
        }
        String changes = describeChanges(before.getInventory(), batch, price, expiryText, lowStock);
        if (changes.isEmpty()) {
            throw new ServiceException("Nothing was changed.");
        }
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                if (!inventoryDao.updateDetails(con, inventoryId, pharmacy.getId(), batch, price, expiryText, lowStock)) {
                    throw new SQLException("Batch " + inventoryId + " was not updated");
                }
                movementDao.insert(con, movement(inventoryId, 0, MovementReason.ADJUST, changes));
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        auditService.log("STOCK_BATCH_UPDATED", "inventory", inventoryId, before.getMedicineLabel() + ": " + changes);
        AvailabilityCache.invalidateMedicine(before.getInventory().getMedicineId());
        checkLowStock(inventoryDao.findItem(inventoryId, pharmacy.getId()));
    }

    /** Stock in: quantity += units (reason ADD). */
    public void stockIn(int inventoryId, int units, String note) throws ServiceException, SQLException {
        if (units < 1 || units > MAX_UNITS) {
            throw new ServiceException("Enter how many units were received (at least 1).");
        }
        changeQuantity(inventoryId, units, MovementReason.ADD, note, "STOCK_IN");
    }

    /** Sold: quantity -= units (reason SALE); never more than is available (reserved units are protected). */
    public void sell(int inventoryId, int units, String note) throws ServiceException, SQLException {
        if (units < 1) {
            throw new ServiceException("Enter how many units were sold (at least 1).");
        }
        changeQuantity(inventoryId, -units, MovementReason.SALE, note, "STOCK_SOLD");
    }

    /** Adjust: quantity = newQuantity (reason ADJUST, change = new − old); not below reserved_qty. A note is required. */
    public void adjust(int inventoryId, int newQuantity, String note) throws ServiceException, SQLException {
        if (ValidationUtil.isBlank(note) || note.trim().length() < 3) {
            throw new ServiceException("Write why the quantity is adjusted (e.g. counted, damaged).");
        }
        InventoryItem item = getItem(inventoryId);
        int reserved = item.getInventory().getReservedQty();
        if (newQuantity < reserved) {
            throw new ServiceException("The quantity cannot be below " + reserved + " because "
                    + reserved + " unit(s) are reserved by patients.");
        }
        int change = newQuantity - item.getInventory().getQuantity();
        if (change == 0) {
            throw new ServiceException("The quantity is already " + newQuantity + ".");
        }
        changeQuantity(inventoryId, change, MovementReason.ADJUST, note, "STOCK_ADJUSTED", newQuantity);
    }

    /** Marks every batch as checked now (updated_at = now) without changing quantities. Returns the count. */
    public int confirmAllStock() throws ServiceException, SQLException {
        Pharmacy pharmacy = requireVerifiedPharmacy();
        int rows = inventoryDao.touchAll(pharmacy.getId());
        for (InventoryItem it : inventoryDao.findItemsByPharmacy(pharmacy.getId())) {
            AvailabilityCache.invalidateMedicine(it.getInventory().getMedicineId());
        }
        auditService.log("STOCK_CONFIRMED", "pharmacies", pharmacy.getId(), rows + " batch(es) confirmed correct");
        return rows;
    }

    private void changeQuantity(int inventoryId, int change, MovementReason reason, String note, String action)
            throws ServiceException, SQLException {
        changeQuantity(inventoryId, change, reason, note, action, null);
    }

    /** newQuantity != null means "set exactly" (adjust); otherwise add change. */
    private void changeQuantity(int inventoryId, int change, MovementReason reason, String note, String action,
            Integer newQuantity) throws ServiceException, SQLException {
        Pharmacy pharmacy = requireVerifiedPharmacy();
        InventoryItem before = getItem(inventoryId);
        String cleanNote = ValidationUtil.trimToNull(note);
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                boolean ok;
                if (newQuantity != null) {
                    ok = inventoryDao.setQuantity(con, inventoryId, pharmacy.getId(), newQuantity);
                } else if (change > 0) {
                    ok = inventoryDao.addQuantity(con, inventoryId, pharmacy.getId(), change);
                } else {
                    ok = inventoryDao.removeAvailable(con, inventoryId, pharmacy.getId(), -change);
                }
                if (!ok) {
                    con.rollback();
                    InventoryItem now = getItem(inventoryId);
                    throw new ServiceException(newQuantity != null
                            ? "The quantity cannot be below the " + now.getInventory().getReservedQty() + " reserved unit(s)."
                            : "Only " + now.getAvailable() + " unit(s) are available to sell ("
                            + now.getInventory().getReservedQty() + " reserved).");
                }
                movementDao.insert(con, movement(inventoryId, change, reason, cleanNote));
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        InventoryItem after = inventoryDao.findItem(inventoryId, pharmacy.getId());
        auditService.log(action, "inventory", inventoryId, before.getMedicineLabel() + ", batch "
                + before.getInventory().getBatchNo() + ": " + before.getInventory().getQuantity() + " → "
                + after.getInventory().getQuantity() + (cleanNote == null ? "" : " (" + cleanNote + ")"));
        AvailabilityCache.invalidateMedicine(before.getInventory().getMedicineId());
        checkLowStock(after);
    }

    // ---------------------------------------------------------------- low stock

    /** Low-stock check after a change made elsewhere (e.g. a reservation pickup). Never fails. */
    public void notifyIfLowStock(int inventoryId) {
        try {
            checkLowStock(inventoryDao.findItem(inventoryId, myPharmacy().getId()));
        } catch (ServiceException | SQLException e) {
            ErrorLog.log("Low-stock check failed", e);
        }
    }

    /**
     * One LOW_STOCK notification per batch per day for the owner when available <= threshold
     * (same rule and text as LowStockJob, see AlertService).
     */
    private void checkLowStock(InventoryItem item) throws SQLException {
        Integer owner = Session.getUserId();
        if (item != null && owner != null) {
            new AlertService().notifyLowStockOnce(owner, item);
        }
    }

    // ---------------------------------------------------------------- helpers

    private StockMovement movement(int inventoryId, int change, MovementReason reason, String note) {
        StockMovement m = new StockMovement();
        m.setInventoryId(inventoryId);
        m.setChangeQty(change);
        m.setReason(reason);
        m.setUserId(Session.getUserId());
        m.setNote(note);
        return m;
    }

    private static String checkBatchNo(String batchNo) throws ServiceException {
        String batch = batchNo == null ? "" : batchNo.trim();
        if (batch.isEmpty()) {
            throw new ServiceException("Enter the batch number printed on the box.");
        }
        if (batch.length() > MAX_BATCH_LENGTH) {
            throw new ServiceException("The batch number is too long (max " + MAX_BATCH_LENGTH + " characters).");
        }
        return batch;
    }

    private static void checkPriceAndThreshold(double price, int lowStock) throws ServiceException {
        if (!(price > 0) || price > 1000000) {
            throw new ServiceException("The price must be more than 0 Tk.");
        }
        if (lowStock < 0) {
            throw new ServiceException("The low-stock alert cannot be negative.");
        }
    }

    private static String describeChanges(Inventory old, String batch, double price, String expiry, int lowStock) {
        StringBuilder sb = new StringBuilder();
        if (!old.getBatchNo().equals(batch)) {
            sb.append("batch ").append(old.getBatchNo()).append(" → ").append(batch).append("; ");
        }
        if (Math.abs(old.getPrice() - price) > 0.0001) {
            sb.append("price ").append(old.getPrice()).append(" → ").append(price).append("; ");
        }
        if (!old.getExpiryDate().equals(expiry)) {
            sb.append("expiry ").append(old.getExpiryDate()).append(" → ").append(expiry).append("; ");
        }
        if (old.getLowStockThreshold() != lowStock) {
            sb.append("alert ").append(old.getLowStockThreshold()).append(" → ").append(lowStock).append("; ");
        }
        return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 2);
    }

    /** The logged-in user's pharmacy, read fresh from the database. */
    private Pharmacy myPharmacy() throws ServiceException, SQLException {
        if (!Session.hasRole(Role.PHARMACY) || Session.getPharmacy() == null) {
            throw new ServiceException("Only a pharmacy account can manage stock.");
        }
        Pharmacy p = pharmacyDao.findById(Session.getPharmacy().getId());
        if (p == null) {
            throw new ServiceException("Your pharmacy no longer exists.");
        }
        return p;
    }

    private Pharmacy requireVerifiedPharmacy() throws ServiceException, SQLException {
        Pharmacy p = myPharmacy();
        if (p.getStatus() != PharmacyStatus.VERIFIED) {
            throw new ServiceException("Only a verified pharmacy can add or change stock. Your pharmacy is "
                    + p.getStatus().toString().toLowerCase() + ".");
        }
        return p;
    }
}
