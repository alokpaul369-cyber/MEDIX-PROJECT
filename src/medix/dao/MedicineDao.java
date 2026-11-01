package medix.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.util.DateUtil;

/**
 * Data access for the medicines table.
 */
public class MedicineDao extends BaseDao<Medicine> {

    @Override
    protected String tableName() {
        return "medicines";
    }

    @Override
    protected Medicine mapRow(ResultSet rs) throws SQLException {
        Medicine m = new Medicine();
        m.setId(rs.getInt("id"));
        m.setBrandName(rs.getString("brand_name"));
        m.setGenericName(rs.getString("generic_name"));
        m.setStrength(rs.getString("strength"));
        m.setForm(getEnum(rs, "form", MedicineForm.class));
        m.setManufacturer(rs.getString("manufacturer"));
        m.setRxRequired(getBoolean(rs, "rx_required"));
        m.setCreatedAt(rs.getString("created_at"));
        return m;
    }

    public int insert(Medicine m) throws SQLException {
        if (m.getCreatedAt() == null) {
            m.setCreatedAt(DateUtil.now());
        }
        int id = executeInsert(
                "INSERT INTO medicines (brand_name, generic_name, strength, form, manufacturer, "
                + "rx_required, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                m.getBrandName(), m.getGenericName(), m.getStrength(), m.getForm(),
                m.getManufacturer(), m.isRxRequired(), m.getCreatedAt());
        m.setId(id);
        return id;
    }

    public boolean update(Medicine m) throws SQLException {
        return executeUpdate(
                "UPDATE medicines SET brand_name = ?, generic_name = ?, strength = ?, form = ?, "
                + "manufacturer = ?, rx_required = ? WHERE id = ?",
                m.getBrandName(), m.getGenericName(), m.getStrength(), m.getForm(),
                m.getManufacturer(), m.isRxRequired(), m.getId()) > 0;
    }

    /**
     * Another medicine with the same brand + strength + form (case-insensitive), ignoring
     * the row with excludeId (use 0 when adding). Null if there is none.
     */
    public Medicine findDuplicate(String brand, String strength, MedicineForm form, int excludeId)
            throws SQLException {
        return findOne("SELECT * FROM medicines WHERE brand_name = ? COLLATE NOCASE "
                + "AND strength = ? COLLATE NOCASE AND form = ? AND id <> ?",
                brand, strength, form, excludeId);
    }

    /**
     * Up to limit medicines whose brand or generic name contains the text; brands that
     * START with the text come first (for autocomplete).
     */
    public List<Medicine> suggest(String text, int limit) throws SQLException {
        String t = text.trim();
        return findList("SELECT * FROM medicines WHERE brand_name LIKE ? OR generic_name LIKE ? "
                + "ORDER BY CASE WHEN brand_name LIKE ? THEN 0 ELSE 1 END, brand_name, strength LIMIT ?",
                "%" + t + "%", "%" + t + "%", t + "%", limit);
    }

    /** Other medicines with the same generic name (ignoring case), e.g. other Omeprazole brands. */
    public List<Medicine> findByGeneric(String genericName, int excludeId) throws SQLException {
        return findList("SELECT * FROM medicines WHERE generic_name = ? COLLATE NOCASE AND id <> ? "
                + "ORDER BY brand_name, strength", genericName.trim(), excludeId);
    }

    /** Brand or generic name contains the text (case-insensitive). */
    public List<Medicine> searchByName(String text) throws SQLException {
        String like = "%" + text.trim() + "%";
        return findList("SELECT * FROM medicines WHERE brand_name LIKE ? OR generic_name LIKE ? "
                + "ORDER BY brand_name", like, like);
    }
}
