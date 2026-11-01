package medix.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.MedicineForm;
import medix.model.PharmacyStatus;
import medix.model.Report;
import medix.model.ReportReason;
import medix.model.ReportStatus;
import medix.model.ReportView;
import medix.model.ReservationStatus;
import medix.util.DateUtil;

/**
 * Data access for the reports table.
 */
public class ReportDao extends BaseDao<Report> {

    @Override
    protected String tableName() {
        return "reports";
    }

    @Override
    protected Report mapRow(ResultSet rs) throws SQLException {
        Report r = new Report();
        r.setId(rs.getInt("id"));
        r.setReporterId(rs.getInt("reporter_id"));
        r.setPharmacyId(rs.getInt("pharmacy_id"));
        r.setReservationId(getInteger(rs, "reservation_id"));
        r.setReason(getEnum(rs, "reason", ReportReason.class));
        r.setDetails(rs.getString("details"));
        r.setStatus(getEnum(rs, "status", ReportStatus.class));
        r.setAdminNote(rs.getString("admin_note"));
        r.setCreatedAt(rs.getString("created_at"));
        r.setResolvedAt(rs.getString("resolved_at"));
        return r;
    }

    public int insert(Report r) throws SQLException {
        if (r.getStatus() == null) {
            r.setStatus(ReportStatus.OPEN);
        }
        if (r.getCreatedAt() == null) {
            r.setCreatedAt(DateUtil.now());
        }
        int id = executeInsert(
                "INSERT INTO reports (reporter_id, pharmacy_id, reservation_id, reason, details, status, "
                + "admin_note, created_at, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.getReporterId(), r.getPharmacyId(), r.getReservationId(), r.getReason(), r.getDetails(),
                r.getStatus(), r.getAdminNote(), r.getCreatedAt(), r.getResolvedAt());
        r.setId(id);
        return id;
    }

    public boolean update(Report r) throws SQLException {
        return executeUpdate(
                "UPDATE reports SET reporter_id = ?, pharmacy_id = ?, reservation_id = ?, reason = ?, "
                + "details = ?, status = ?, admin_note = ?, resolved_at = ? WHERE id = ?",
                r.getReporterId(), r.getPharmacyId(), r.getReservationId(), r.getReason(), r.getDetails(),
                r.getStatus(), r.getAdminNote(), r.getResolvedAt(), r.getId()) > 0;
    }

    /** Reports about the pharmacy that an admin upheld (status RESOLVED) with resolved_at in [from, to). */
    public int countUpheld(int pharmacyId, String from, String to) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM reports WHERE pharmacy_id = ? AND status = 'RESOLVED' "
                + "AND resolved_at >= ? AND resolved_at < ?", pharmacyId, from, to);
    }

    /** True if this patient already has an OPEN report about this reservation. */
    public boolean existsOpen(int reporterId, int reservationId) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM reports WHERE reporter_id = ? AND reservation_id = ? AND status = 'OPEN'",
                reporterId, reservationId) > 0;
    }

    public List<Report> findByStatus(ReportStatus status) throws SQLException {
        return findList("SELECT * FROM reports WHERE status = ? ORDER BY id", status);
    }

    public List<Report> findByPharmacy(int pharmacyId) throws SQLException {
        return findList("SELECT * FROM reports WHERE pharmacy_id = ? ORDER BY id DESC", pharmacyId);
    }

    public int countByStatus(ReportStatus status) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM reports WHERE status = ?", status);
    }

    /** Every report about the pharmacy that an admin upheld (status RESOLVED), any time. */
    public int countUpheld(int pharmacyId) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM reports WHERE pharmacy_id = ? AND status = 'RESOLVED'", pharmacyId);
    }

    /**
     * Admin decision: OPEN → RESOLVED (upheld) or DISMISSED with the admin note and resolved_at.
     * Returns false when the report is no longer OPEN (another admin handled it).
     */
    public boolean close(int id, ReportStatus newStatus, String adminNote, String resolvedAt) throws SQLException {
        return executeUpdate("UPDATE reports SET status = ?, admin_note = ?, resolved_at = ? "
                + "WHERE id = ? AND status = 'OPEN'", newStatus, adminNote, resolvedAt, id) > 0;
    }

    // ---------------------------------------------------------------- views for the admin

    private static final String VIEW_SELECT = "SELECT rp.*, u.full_name AS reporter_name, u.email AS reporter_email, "
            + "p.name AS pharmacy_name, p.status AS pharmacy_status, p.trust_score, p.owner_id, "
            + "r.code AS reservation_code, r.quantity AS reservation_qty, r.status AS reservation_status, "
            + "r.created_at AS reservation_created, m.brand_name, m.strength, m.form "
            + "FROM reports rp "
            + "JOIN users u ON u.id = rp.reporter_id "
            + "JOIN pharmacies p ON p.id = rp.pharmacy_id "
            + "LEFT JOIN reservations r ON r.id = rp.reservation_id "
            + "LEFT JOIN medicines m ON m.id = r.medicine_id ";

    private ReportView mapView(ResultSet rs) throws SQLException {
        String code = rs.getString("reservation_code");
        String summary = null;
        if (code != null) {
            summary = rs.getString("brand_name") + " " + rs.getString("strength") + " "
                    + getEnum(rs, "form", MedicineForm.class) + " × " + rs.getInt("reservation_qty") + " · "
                    + getEnum(rs, "reservation_status", ReservationStatus.class) + " · "
                    + DateUtil.toDisplay(rs.getString("reservation_created"));
        }
        return new ReportView(mapRow(rs), rs.getString("reporter_name"), rs.getString("reporter_email"),
                rs.getString("pharmacy_name"), getEnum(rs, "pharmacy_status", PharmacyStatus.class),
                rs.getInt("trust_score"), rs.getInt("owner_id"), code, summary);
    }

    /** Reports with the status (null = all), open ones first, then newest first. */
    public List<ReportView> findViews(ReportStatus status) throws SQLException {
        return query(VIEW_SELECT + "WHERE (? IS NULL OR rp.status = ?) "
                + "ORDER BY CASE WHEN rp.status = 'OPEN' THEN 0 ELSE 1 END, rp.id DESC",
                this::mapView, status, status);
    }

    /** The newest OPEN reports (dashboard). */
    public List<ReportView> findLatestOpen(int limit) throws SQLException {
        return query(VIEW_SELECT + "WHERE rp.status = 'OPEN' ORDER BY rp.id DESC LIMIT ?", this::mapView, limit);
    }

    public ReportView findView(int id) throws SQLException {
        List<ReportView> list = query(VIEW_SELECT + "WHERE rp.id = ?", this::mapView, id);
        return list.isEmpty() ? null : list.get(0);
    }
}
