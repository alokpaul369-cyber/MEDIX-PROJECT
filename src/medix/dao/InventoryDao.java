package medix.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.BatchAlert;
import medix.model.Inventory;
import medix.model.InventoryItem;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.model.PharmacyStock;
import medix.util.DBConnection;
import medix.util.DateUtil;

/**
 * Data access for the inventory table.
 *
 * Quantity changes are single conditional UPDATEs (e.g. "only if enough is available"),
 * so two people selling at the same time can never take stock below what is reserved.
 * They return false when the condition failed.
 */
public class InventoryDao extends BaseDao<Inventory> {

    private static final String ITEM_SELECT = "SELECT i.*, m.brand_name, m.generic_name, m.strength, m.form, "
            + "m.manufacturer, m.rx_required, m.created_at AS medicine_created_at "
            + "FROM inventory i JOIN medicines m ON m.id = i.medicine_id ";

    @Override
    protected String tableName() {
        return "inventory";
    }

    @Override
    protected Inventory mapRow(ResultSet rs) throws SQLException {
        Inventory i = new Inventory();
        i.setId(rs.getInt("id"));
        i.setPharmacyId(rs.getInt("pharmacy_id"));
        i.setMedicineId(rs.getInt("medicine_id"));
        i.setBatchNo(rs.getString("batch_no"));
        i.setQuantity(rs.getInt("quantity"));
        i.setReservedQty(rs.getInt("reserved_qty"));
        i.setPrice(rs.getDouble("price"));
        i.setExpiryDate(rs.getString("expiry_date"));
        i.setLowStockThreshold(rs.getInt("low_stock_threshold"));
        i.setUpdatedAt(rs.getString("updated_at"));
        return i;
    }

    private InventoryItem mapItem(ResultSet rs) throws SQLException {
        Inventory i = mapRow(rs);
        Medicine m = new Medicine();
        m.setId(i.getMedicineId());
        m.setBrandName(rs.getString("brand_name"));
        m.setGenericName(rs.getString("generic_name"));
        m.setStrength(rs.getString("strength"));
        m.setForm(getEnum(rs, "form", MedicineForm.class));
        m.setManufacturer(rs.getString("manufacturer"));
        m.setRxRequired(getBoolean(rs, "rx_required"));
        m.setCreatedAt(rs.getString("medicine_created_at"));
        return new InventoryItem(i, m);
    }

    public int insert(Inventory i) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return insert(con, i);
        }
    }

    /** Insert on the caller's connection (for transactions). */
    public int insert(Connection con, Inventory i) throws SQLException {
        i.setUpdatedAt(DateUtil.now());
        int id = executeInsert(con,
                "INSERT INTO inventory (pharmacy_id, medicine_id, batch_no, quantity, reserved_qty, price, "
                + "expiry_date, low_stock_threshold, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                i.getPharmacyId(), i.getMedicineId(), i.getBatchNo(), i.getQuantity(), i.getReservedQty(),
                i.getPrice(), i.getExpiryDate(), i.getLowStockThreshold(), i.getUpdatedAt());
        i.setId(id);
        return id;
    }

    public boolean update(Inventory i) throws SQLException {
        i.setUpdatedAt(DateUtil.now());
        return executeUpdate(
                "UPDATE inventory SET pharmacy_id = ?, medicine_id = ?, batch_no = ?, quantity = ?, "
                + "reserved_qty = ?, price = ?, expiry_date = ?, low_stock_threshold = ?, updated_at = ? "
                + "WHERE id = ?",
                i.getPharmacyId(), i.getMedicineId(), i.getBatchNo(), i.getQuantity(), i.getReservedQty(),
                i.getPrice(), i.getExpiryDate(), i.getLowStockThreshold(), i.getUpdatedAt(), i.getId()) > 0;
    }

    public List<Inventory> findByPharmacy(int pharmacyId) throws SQLException {
        return findList("SELECT * FROM inventory WHERE pharmacy_id = ? ORDER BY id", pharmacyId);
    }

    public int countByMedicine(int medicineId) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM inventory WHERE medicine_id = ?", medicineId);
    }

    public List<Inventory> findByMedicine(int medicineId) throws SQLException {
        return findList("SELECT * FROM inventory WHERE medicine_id = ? ORDER BY id", medicineId);
    }

    // ---------------------------------------------------------------- inventory screen

    /** All batches of a pharmacy with their medicine, by medicine name then expiry. */
    public List<InventoryItem> findItemsByPharmacy(int pharmacyId) throws SQLException {
        return query(ITEM_SELECT + "WHERE i.pharmacy_id = ? ORDER BY m.brand_name, m.strength, i.expiry_date",
                this::mapItem, pharmacyId);
    }

    /** One batch, only if it belongs to the pharmacy; null otherwise. */
    public InventoryItem findItem(int inventoryId, int pharmacyId) throws SQLException {
        List<InventoryItem> list = query(ITEM_SELECT + "WHERE i.id = ? AND i.pharmacy_id = ?",
                this::mapItem, inventoryId, pharmacyId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Same pharmacy + medicine + batch number (ignoring case), except excludeId (0 = none). */
    public Inventory findBatch(int pharmacyId, int medicineId, String batchNo, int excludeId) throws SQLException {
        return findOne("SELECT * FROM inventory WHERE pharmacy_id = ? AND medicine_id = ? "
                + "AND batch_no = ? COLLATE NOCASE AND id <> ?", pharmacyId, medicineId, batchNo, excludeId);
    }

    /** quantity += units. */
    public boolean addQuantity(Connection con, int id, int pharmacyId, int units) throws SQLException {
        return executeUpdate(con, "UPDATE inventory SET quantity = quantity + ?, updated_at = ? "
                + "WHERE id = ? AND pharmacy_id = ?", units, DateUtil.now(), id, pharmacyId) > 0;
    }

    /** quantity -= units, only if at least that many are available (reserved units are protected). */
    public boolean removeAvailable(Connection con, int id, int pharmacyId, int units) throws SQLException {
        return executeUpdate(con, "UPDATE inventory SET quantity = quantity - ?, updated_at = ? "
                + "WHERE id = ? AND pharmacy_id = ? AND quantity - reserved_qty >= ?",
                units, DateUtil.now(), id, pharmacyId, units) > 0;
    }

    /** quantity = newQuantity, only if it is not below reserved_qty. */
    public boolean setQuantity(Connection con, int id, int pharmacyId, int newQuantity) throws SQLException {
        return executeUpdate(con, "UPDATE inventory SET quantity = ?, updated_at = ? "
                + "WHERE id = ? AND pharmacy_id = ? AND reserved_qty <= ?",
                newQuantity, DateUtil.now(), id, pharmacyId, newQuantity) > 0;
    }

    /** Batch number, price, expiry and low-stock threshold (not the quantity). */
    public boolean updateDetails(Connection con, int id, int pharmacyId, String batchNo, double price,
            String expiryDate, int lowStockThreshold) throws SQLException {
        return executeUpdate(con, "UPDATE inventory SET batch_no = ?, price = ?, expiry_date = ?, "
                + "low_stock_threshold = ?, updated_at = ? WHERE id = ? AND pharmacy_id = ?",
                batchNo, price, expiryDate, lowStockThreshold, DateUtil.now(), id, pharmacyId) > 0;
    }

    // ---------------------------------------------------------------- patient search

    /**
     * Every VERIFIED pharmacy that has the medicine: one row per pharmacy over its batches that
     * expire AFTER today and have units available. available = SUM(quantity − reserved_qty),
     * lowestPrice = MIN(price), lastUpdated = MAX(updated_at). Pharmacies without coordinates
     * use their area's centre. Distance is added later by SearchEngine.
     */
    public List<PharmacyStock> findStockedPharmacies(int medicineId) throws SQLException {
        return query("SELECT p.id, p.name, p.phone, p.address, p.open_time, p.close_time, p.trust_score, "
                + "a.name AS area_name, COALESCE(p.latitude, a.latitude) AS lat, "
                + "COALESCE(p.longitude, a.longitude) AS lon, "
                + "SUM(i.quantity - i.reserved_qty) AS available, MIN(i.price) AS lowest_price, "
                + "MAX(i.updated_at) AS last_updated "
                + "FROM inventory i "
                + "JOIN pharmacies p ON p.id = i.pharmacy_id "
                + "LEFT JOIN areas a ON a.id = p.area_id "
                + "WHERE i.medicine_id = ? AND p.status = 'VERIFIED' AND i.expiry_date > ? "
                + "AND i.quantity - i.reserved_qty > 0 "
                + "GROUP BY p.id HAVING SUM(i.quantity - i.reserved_qty) > 0 "
                + "AND COALESCE(p.latitude, a.latitude) IS NOT NULL",
                rs -> {
                    PharmacyStock s = new PharmacyStock();
                    s.setPharmacyId(rs.getInt("id"));
                    s.setPharmacyName(rs.getString("name"));
                    s.setPhone(rs.getString("phone"));
                    s.setAddress(rs.getString("address"));
                    s.setOpenTime(rs.getString("open_time"));
                    s.setCloseTime(rs.getString("close_time"));
                    s.setTrustScore(rs.getInt("trust_score"));
                    s.setAreaName(rs.getString("area_name") == null ? "" : rs.getString("area_name"));
                    s.setLatitude(rs.getDouble("lat"));
                    s.setLongitude(rs.getDouble("lon"));
                    s.setAvailable(rs.getInt("available"));
                    s.setLowestPrice(rs.getDouble("lowest_price"));
                    s.setLastUpdated(rs.getString("last_updated"));
                    return s;
                }, medicineId, DateUtil.today());
    }

    // ---------------------------------------------------------------- background jobs

    private static final String ALERT_SELECT = "SELECT i.*, m.brand_name, m.generic_name, m.strength, m.form, "
            + "m.manufacturer, m.rx_required, m.created_at AS medicine_created_at, p.owner_id, p.name AS pharmacy_name "
            + "FROM inventory i JOIN medicines m ON m.id = i.medicine_id JOIN pharmacies p ON p.id = i.pharmacy_id "
            + "WHERE p.status = 'VERIFIED' ";

    private BatchAlert mapAlert(ResultSet rs) throws SQLException {
        return new BatchAlert(mapItem(rs), rs.getInt("owner_id"), rs.getString("pharmacy_name"));
    }

    /** Non-expired batches of VERIFIED pharmacies with available (quantity − reserved) <= threshold. */
    public List<BatchAlert> findLowStockAlerts() throws SQLException {
        return query(ALERT_SELECT + "AND i.expiry_date > ? AND i.quantity - i.reserved_qty <= i.low_stock_threshold "
                + "ORDER BY i.pharmacy_id, i.id", this::mapAlert, DateUtil.today());
    }

    /** Batches with units left that expire within `days` days or have already expired. */
    public List<BatchAlert> findExpiryAlerts(int days) throws SQLException {
        return query(ALERT_SELECT + "AND i.quantity > 0 AND i.expiry_date <= ? ORDER BY i.expiry_date, i.id",
                this::mapAlert, DateUtil.todayPlusDays(days));
    }

    // ---------------------------------------------------------------- reservations

    /** Batches of the medicine at the pharmacy that expire AFTER today, earliest expiry first (FEFO). */
    public List<Inventory> findValidBatches(int pharmacyId, int medicineId) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return findValidBatches(con, pharmacyId, medicineId);
        }
    }

    /** Same on the caller's connection (re-checked inside the reservation transaction). */
    public List<Inventory> findValidBatches(Connection con, int pharmacyId, int medicineId) throws SQLException {
        return findList(con, "SELECT * FROM inventory WHERE pharmacy_id = ? AND medicine_id = ? AND expiry_date > ? "
                + "ORDER BY expiry_date, id", pharmacyId, medicineId, DateUtil.today());
    }

    /**
     * reserved_qty += units, only if that many are still available. Does not touch updated_at
     * (a reservation is not a stock check). Returns false when not enough units are left.
     */
    public boolean reserve(Connection con, int id, int units) throws SQLException {
        return executeUpdate(con, "UPDATE inventory SET reserved_qty = reserved_qty + ? "
                + "WHERE id = ? AND quantity - reserved_qty >= ?", units, id, units) > 0;
    }

    /**
     * Pickup: the reserved units leave the shop. quantity −= units AND reserved_qty −= units,
     * updated_at = now (the pharmacist handled the stock). False if fewer units were reserved.
     */
    public boolean pickUp(Connection con, int id, int units) throws SQLException {
        return executeUpdate(con, "UPDATE inventory SET quantity = quantity - ?, reserved_qty = reserved_qty - ?, "
                + "updated_at = ? WHERE id = ? AND reserved_qty >= ? AND quantity >= ?",
                units, units, DateUtil.now(), id, units, units) > 0;
    }

    /** Newest updated_at of the pharmacy's batches, or null when it has no stock rows. */
    public String lastStockUpdate(int pharmacyId) throws SQLException {
        try (Connection con = DBConnection.getConnection();
                java.sql.PreparedStatement ps = con.prepareStatement(
                        "SELECT MAX(updated_at) FROM inventory WHERE pharmacy_id = ?")) {
            ps.setInt(1, pharmacyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** reserved_qty −= units (never below 0). Returns false when fewer units were reserved. */
    public boolean release(Connection con, int id, int units) throws SQLException {
        return executeUpdate(con, "UPDATE inventory SET reserved_qty = reserved_qty - ? "
                + "WHERE id = ? AND reserved_qty >= ?", units, id, units) > 0;
    }

    /** Demo data only: backdates every batch of the pharmacy (e.g. stock not updated for 2 days). */
    public int setUpdatedAt(int pharmacyId, String dateTime) throws SQLException {
        return executeUpdate("UPDATE inventory SET updated_at = ? WHERE pharmacy_id = ?", dateTime, pharmacyId);
    }

    /** "Confirm all stock is correct": updated_at = now on every batch; returns the row count. */
    public int touchAll(int pharmacyId) throws SQLException {
        return executeUpdate("UPDATE inventory SET updated_at = ? WHERE pharmacy_id = ?", DateUtil.now(), pharmacyId);
    }
}
