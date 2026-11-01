package medix.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.util.DBConnection;
import medix.util.DateUtil;

/**
 * Data access for the pharmacies table.
 */
public class PharmacyDao extends BaseDao<Pharmacy> {

    @Override
    protected String tableName() {
        return "pharmacies";
    }

    @Override
    protected Pharmacy mapRow(ResultSet rs) throws SQLException {
        Pharmacy p = new Pharmacy();
        p.setId(rs.getInt("id"));
        p.setOwnerId(rs.getInt("owner_id"));
        p.setName(rs.getString("name"));
        p.setLicenseNo(rs.getString("license_no"));
        p.setLicenseDocPath(rs.getString("license_doc_path"));
        p.setAddress(rs.getString("address"));
        p.setAreaId(getInteger(rs, "area_id"));
        p.setLatitude(getDouble(rs, "latitude"));
        p.setLongitude(getDouble(rs, "longitude"));
        p.setPhone(rs.getString("phone"));
        p.setOpenTime(rs.getString("open_time"));
        p.setCloseTime(rs.getString("close_time"));
        p.setStatus(getEnum(rs, "status", PharmacyStatus.class));
        p.setTrustScore(rs.getInt("trust_score"));
        p.setVerifiedBy(getInteger(rs, "verified_by"));
        p.setVerifiedAt(rs.getString("verified_at"));
        p.setRejectReason(rs.getString("reject_reason"));
        p.setStalePenalty(rs.getInt("stale_penalty"));
        p.setCreatedAt(rs.getString("created_at"));
        return p;
    }

    public int insert(Pharmacy p) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return insert(con, p);
        }
    }

    /** Insert on the caller's connection (for transactions). */
    public int insert(Connection con, Pharmacy p) throws SQLException {
        if (p.getStatus() == null) {
            p.setStatus(PharmacyStatus.PENDING);
        }
        if (p.getCreatedAt() == null) {
            p.setCreatedAt(DateUtil.now());
        }
        int id = executeInsert(con,
                "INSERT INTO pharmacies (owner_id, name, license_no, license_doc_path, address, area_id, "
                + "latitude, longitude, phone, open_time, close_time, status, trust_score, verified_by, "
                + "verified_at, reject_reason, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.getOwnerId(), p.getName(), p.getLicenseNo(), p.getLicenseDocPath(), p.getAddress(),
                p.getAreaId(), p.getLatitude(), p.getLongitude(), p.getPhone(), p.getOpenTime(),
                p.getCloseTime(), p.getStatus(), p.getTrustScore(), p.getVerifiedBy(),
                p.getVerifiedAt(), p.getRejectReason(), p.getCreatedAt());
        p.setId(id);
        return id;
    }

    public boolean update(Pharmacy p) throws SQLException {
        return executeUpdate(
                "UPDATE pharmacies SET owner_id = ?, name = ?, license_no = ?, license_doc_path = ?, "
                + "address = ?, area_id = ?, latitude = ?, longitude = ?, phone = ?, open_time = ?, "
                + "close_time = ?, status = ?, trust_score = ?, verified_by = ?, verified_at = ?, "
                + "reject_reason = ? WHERE id = ?",
                p.getOwnerId(), p.getName(), p.getLicenseNo(), p.getLicenseDocPath(), p.getAddress(),
                p.getAreaId(), p.getLatitude(), p.getLongitude(), p.getPhone(), p.getOpenTime(),
                p.getCloseTime(), p.getStatus(), p.getTrustScore(), p.getVerifiedBy(),
                p.getVerifiedAt(), p.getRejectReason(), p.getId()) > 0;
    }

    /**
     * Fields the owner may edit (name, phone, hours, address, area, coordinates) on the caller's
     * connection. License number, status and verification fields are not touched.
     */
    public boolean updateProfile(Connection con, Pharmacy p) throws SQLException {
        return executeUpdate(con, "UPDATE pharmacies SET name = ?, phone = ?, open_time = ?, close_time = ?, "
                + "address = ?, area_id = ?, latitude = ?, longitude = ? WHERE id = ?",
                p.getName(), p.getPhone(), p.getOpenTime(), p.getCloseTime(), p.getAddress(), p.getAreaId(),
                p.getLatitude(), p.getLongitude(), p.getId()) > 0;
    }

    public boolean updateTrustScore(int pharmacyId, int score) throws SQLException {
        return executeUpdate("UPDATE pharmacies SET trust_score = ? WHERE id = ?", score, pharmacyId) > 0;
    }

    public boolean updateStalePenalty(int pharmacyId, int penalty) throws SQLException {
        return executeUpdate("UPDATE pharmacies SET stale_penalty = ? WHERE id = ?", penalty, pharmacyId) > 0;
    }

    /** Sets license_doc_path on the caller's connection (for transactions). */
    public boolean updateLicenseDocPath(Connection con, int pharmacyId, String path) throws SQLException {
        return executeUpdate(con, "UPDATE pharmacies SET license_doc_path = ? WHERE id = ?", path, pharmacyId) > 0;
    }

    /** Case-insensitive check, e.g. "dl-123456" and "DL-123456" are the same license. */
    public boolean licenseNoExists(String licenseNo) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM pharmacies WHERE license_no = ? COLLATE NOCASE", licenseNo) > 0;
    }

    public Pharmacy findByOwnerId(int ownerId) throws SQLException {
        return findOne("SELECT * FROM pharmacies WHERE owner_id = ?", ownerId);
    }

    public List<Pharmacy> findByStatus(PharmacyStatus status) throws SQLException {
        return findList("SELECT * FROM pharmacies WHERE status = ? ORDER BY id", status);
    }

    /**
     * Pharmacies with the status (null = any) whose name or license number contains
     * the text (null/empty = no text filter), oldest application first.
     */
    public List<Pharmacy> search(PharmacyStatus status, String text) throws SQLException {
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        if (status == null) {
            return findList("SELECT * FROM pharmacies WHERE (name LIKE ? OR license_no LIKE ?) ORDER BY id",
                    like, like);
        }
        return findList("SELECT * FROM pharmacies WHERE status = ? AND (name LIKE ? OR license_no LIKE ?) "
                + "ORDER BY id", status, like, like);
    }

    public int countByStatus(PharmacyStatus status) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM pharmacies WHERE status = ?", status);
    }

    /** How many different areas have a pharmacy with this status. */
    public int countAreasWithStatus(PharmacyStatus status) throws SQLException {
        return queryInt("SELECT COUNT(DISTINCT area_id) FROM pharmacies WHERE status = ? AND area_id IS NOT NULL",
                status);
    }

    /**
     * Changes the status only if it is still expectedStatus (so two admins cannot
     * overwrite each other). Returns false when the row was changed meanwhile.
     */
    public boolean updateStatus(int id, PharmacyStatus expectedStatus, PharmacyStatus newStatus,
            Integer verifiedBy, String verifiedAt, String rejectReason) throws SQLException {
        return executeUpdate("UPDATE pharmacies SET status = ?, verified_by = ?, verified_at = ?, "
                + "reject_reason = ? WHERE id = ? AND status = ?",
                newStatus, verifiedBy, verifiedAt, rejectReason, id, expectedStatus) > 0;
    }
}
