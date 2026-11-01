package medix.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.MedicineForm;
import medix.model.Reservation;
import medix.model.ReservationStatus;
import medix.model.ReservationView;
import medix.util.DBConnection;
import medix.util.DateUtil;

/**
 * Data access for the reservations table.
 */
public class ReservationDao extends BaseDao<Reservation> {

    /** SQL list of the statuses that still hold units. */
    public static final String ACTIVE_STATUSES = "('PENDING', 'CONFIRMED', 'READY')";

    private static final String VIEW_SELECT = "SELECT r.*, m.brand_name, m.strength, m.form, p.name AS pharmacy_name, "
            + "p.phone AS pharmacy_phone, p.owner_id, i.price AS unit_price, u.full_name AS patient_name, u.phone AS patient_phone "
            + "FROM reservations r "
            + "JOIN medicines m ON m.id = r.medicine_id "
            + "JOIN pharmacies p ON p.id = r.pharmacy_id "
            + "JOIN users u ON u.id = r.patient_id "
            + "LEFT JOIN inventory i ON i.id = r.inventory_id ";

    @Override
    protected String tableName() {
        return "reservations";
    }

    @Override
    protected Reservation mapRow(ResultSet rs) throws SQLException {
        Reservation r = new Reservation();
        r.setId(rs.getInt("id"));
        r.setCode(rs.getString("code"));
        r.setPatientId(rs.getInt("patient_id"));
        r.setPharmacyId(rs.getInt("pharmacy_id"));
        r.setMedicineId(rs.getInt("medicine_id"));
        r.setInventoryId(getInteger(rs, "inventory_id"));
        r.setQuantity(rs.getInt("quantity"));
        r.setStatus(getEnum(rs, "status", ReservationStatus.class));
        r.setCreatedAt(rs.getString("created_at"));
        r.setExpiresAt(rs.getString("expires_at"));
        r.setUpdatedAt(rs.getString("updated_at"));
        r.setNote(rs.getString("note"));
        r.setExtended(getBoolean(rs, "extended"));
        r.setCancelledBy(rs.getString("cancelled_by"));
        r.setCancelReason(rs.getString("cancel_reason"));
        return r;
    }

    private ReservationView mapView(ResultSet rs) throws SQLException {
        Reservation r = mapRow(rs);
        String form = getEnum(rs, "form", MedicineForm.class).toString();
        String shortName = rs.getString("brand_name") + " " + rs.getString("strength");
        return new ReservationView(r, shortName + " " + form, shortName, rs.getString("pharmacy_name"),
                rs.getString("pharmacy_phone"), rs.getInt("owner_id"), getDouble(rs, "unit_price"),
                rs.getString("patient_name"), rs.getString("patient_phone"));
    }

    public int insert(Reservation r) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return insert(con, r);
        }
    }

    /** Insert on the caller's connection (for transactions). */
    public int insert(Connection con, Reservation r) throws SQLException {
        if (r.getStatus() == null) {
            r.setStatus(ReservationStatus.PENDING);
        }
        String now = DateUtil.now();
        if (r.getCreatedAt() == null) {
            r.setCreatedAt(now);
        }
        r.setUpdatedAt(now);
        int id = executeInsert(con,
                "INSERT INTO reservations (code, patient_id, pharmacy_id, medicine_id, inventory_id, quantity, "
                + "status, created_at, expires_at, updated_at, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.getCode(), r.getPatientId(), r.getPharmacyId(), r.getMedicineId(), r.getInventoryId(),
                r.getQuantity(), r.getStatus(), r.getCreatedAt(), r.getExpiresAt(), r.getUpdatedAt(),
                r.getNote());
        r.setId(id);
        return id;
    }

    public boolean update(Reservation r) throws SQLException {
        r.setUpdatedAt(DateUtil.now());
        return executeUpdate(
                "UPDATE reservations SET code = ?, patient_id = ?, pharmacy_id = ?, medicine_id = ?, "
                + "inventory_id = ?, quantity = ?, status = ?, expires_at = ?, updated_at = ?, note = ? "
                + "WHERE id = ?",
                r.getCode(), r.getPatientId(), r.getPharmacyId(), r.getMedicineId(), r.getInventoryId(),
                r.getQuantity(), r.getStatus(), r.getExpiresAt(), r.getUpdatedAt(), r.getNote(),
                r.getId()) > 0;
    }

    public boolean updateCode(Connection con, int id, String code) throws SQLException {
        return executeUpdate(con, "UPDATE reservations SET code = ? WHERE id = ?", code, id) > 0;
    }

    /** Moves an ACTIVE reservation to newStatus; false if it is no longer active. */
    public boolean changeActiveStatus(Connection con, int id, ReservationStatus newStatus) throws SQLException {
        return executeUpdate(con, "UPDATE reservations SET status = ?, updated_at = ? WHERE id = ? AND status IN "
                + ACTIVE_STATUSES, newStatus, DateUtil.now(), id) > 0;
    }

    /**
     * Moves the reservation to `to` only if its status is one of `from` (so a stale screen cannot
     * skip a step). Sets updated_at. Returns false when the status was not one of `from`.
     */
    public boolean changeStatus(Connection con, int id, List<ReservationStatus> from, ReservationStatus to)
            throws SQLException {
        Object[] params = new Object[3 + from.size()];
        params[0] = to;
        params[1] = DateUtil.now();
        params[2] = id;
        for (int i = 0; i < from.size(); i++) {
            params[3 + i] = from.get(i);
        }
        return executeUpdate(con, "UPDATE reservations SET status = ?, updated_at = ? WHERE id = ? AND status IN "
                + placeholders(from.size()), params) > 0;
    }

    /** Active (PENDING / CONFIRMED / READY) reservations whose hold ended before `now`, oldest first. */
    public List<Reservation> findOverdueActive(String now) throws SQLException {
        return findList("SELECT * FROM reservations WHERE status IN " + ACTIVE_STATUSES + " AND expires_at < ? "
                + "ORDER BY expires_at, id", now);
    }

    /** Who cancelled (PATIENT / PHARMACY / SYSTEM) and why. */
    public boolean setCancelInfo(Connection con, int id, String cancelledBy, String reason) throws SQLException {
        return executeUpdate(con, "UPDATE reservations SET cancelled_by = ?, cancel_reason = ? WHERE id = ?",
                cancelledBy, reason, id) > 0;
    }

    /** New expires_at, only once per reservation and only while active. */
    public boolean extendOnce(Connection con, int id, String newExpiresAt) throws SQLException {
        return executeUpdate(con, "UPDATE reservations SET expires_at = ?, extended = 1, updated_at = ? "
                + "WHERE id = ? AND extended = 0 AND status IN " + ACTIVE_STATUSES, newExpiresAt, DateUtil.now(), id) > 0;
    }

    /** A pharmacy's reservations with these statuses (empty = all): active first, then newest. */
    public List<ReservationView> findViewsByPharmacy(int pharmacyId, List<ReservationStatus> statuses)
            throws SQLException {
        Object[] params = new Object[1 + statuses.size()];
        params[0] = pharmacyId;
        for (int i = 0; i < statuses.size(); i++) {
            params[1 + i] = statuses.get(i);
        }
        return query(VIEW_SELECT + "WHERE r.pharmacy_id = ? "
                + (statuses.isEmpty() ? "" : "AND r.status IN " + placeholders(statuses.size()) + " ")
                + "ORDER BY CASE WHEN r.status IN " + ACTIVE_STATUSES + " THEN 0 ELSE 1 END, r.expires_at, r.id DESC",
                this::mapView, params);
    }

    /** The reservation with this code at this pharmacy (codes are unique), or null. */
    public Reservation findByCodeAtPharmacy(String code, int pharmacyId) throws SQLException {
        return findOne("SELECT * FROM reservations WHERE code = ? COLLATE NOCASE AND pharmacy_id = ?", code, pharmacyId);
    }

    /** Reservations of the pharmacy currently in one of the statuses. */
    public int countWithStatus(int pharmacyId, ReservationStatus... statuses) throws SQLException {
        Object[] params = new Object[1 + statuses.length];
        params[0] = pharmacyId;
        System.arraycopy(statuses, 0, params, 1, statuses.length);
        return queryInt("SELECT COUNT(*) FROM reservations WHERE pharmacy_id = ? AND status IN "
                + placeholders(statuses.length), params);
    }

    /**
     * Reservations that reached `status` (by their last change, updated_at) in [from, to).
     * cancelledBy / reasonPrefix narrow cancellations (null = any).
     */
    public int countChanged(int pharmacyId, ReservationStatus status, String cancelledBy, String reasonPrefix,
            String from, String to) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM reservations WHERE pharmacy_id = ? AND status = ? "
                + "AND updated_at >= ? AND updated_at < ? "
                + "AND (? IS NULL OR cancelled_by = ?) AND (? IS NULL OR cancel_reason LIKE ?)",
                pharmacyId, status, from, to, cancelledBy, cancelledBy, reasonPrefix,
                reasonPrefix == null ? null : reasonPrefix + "%");
    }

    /** Demo data only: moves the hold end (e.g. into the past so the expiry job expires it). */
    public boolean setExpiresAt(int id, String expiresAt) throws SQLException {
        return executeUpdate("UPDATE reservations SET expires_at = ? WHERE id = ?", expiresAt, id) > 0;
    }

    /** Reservations of every pharmacy created in [from, to) that are now in one of the statuses. */
    public int countCreatedBetween(String from, String to, ReservationStatus... statuses) throws SQLException {
        Object[] params = new Object[2 + statuses.length];
        params[0] = from;
        params[1] = to;
        System.arraycopy(statuses, 0, params, 2, statuses.length);
        return queryInt("SELECT COUNT(*) FROM reservations WHERE created_at >= ? AND created_at < ? AND status IN "
                + placeholders(statuses.length), params);
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.append(")").toString();
    }

    public Reservation findByCode(String code) throws SQLException {
        return findOne("SELECT * FROM reservations WHERE code = ?", code);
    }

    public int countByMedicine(int medicineId) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM reservations WHERE medicine_id = ?", medicineId);
    }

    /** Active (PENDING / CONFIRMED / READY) reservations of a patient. */
    public int countActiveByPatient(Connection con, int patientId) throws SQLException {
        return queryInt(con, "SELECT COUNT(*) FROM reservations WHERE patient_id = ? AND status IN "
                + ACTIVE_STATUSES, patientId);
    }

    /** The patient's active reservation of this medicine at this pharmacy, or null. */
    public Reservation findActive(Connection con, int patientId, int pharmacyId, int medicineId) throws SQLException {
        return findOne(con, "SELECT * FROM reservations WHERE patient_id = ? AND pharmacy_id = ? AND medicine_id = ? "
                + "AND status IN " + ACTIVE_STATUSES + " ORDER BY id DESC LIMIT 1", patientId, pharmacyId, medicineId);
    }

    public List<Reservation> findByPatient(int patientId) throws SQLException {
        return findList("SELECT * FROM reservations WHERE patient_id = ? ORDER BY id DESC", patientId);
    }

    public List<Reservation> findByPharmacy(int pharmacyId) throws SQLException {
        return findList("SELECT * FROM reservations WHERE pharmacy_id = ? ORDER BY id DESC", pharmacyId);
    }

    /** A patient's reservations with medicine and pharmacy names: active first, then newest first. */
    public List<ReservationView> findViewsByPatient(int patientId) throws SQLException {
        return query(VIEW_SELECT + "WHERE r.patient_id = ? "
                + "ORDER BY CASE WHEN r.status IN " + ACTIVE_STATUSES + " THEN 0 ELSE 1 END, r.id DESC",
                this::mapView, patientId);
    }

    public ReservationView findView(int reservationId) throws SQLException {
        List<ReservationView> list = query(VIEW_SELECT + "WHERE r.id = ?", this::mapView, reservationId);
        return list.isEmpty() ? null : list.get(0);
    }
}
