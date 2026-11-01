package medix.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.MovementReason;
import medix.model.StockMovement;
import medix.util.DBConnection;
import medix.util.DateUtil;

/**
 * Data access for the stock_movements table.
 */
public class StockMovementDao extends BaseDao<StockMovement> {

    @Override
    protected String tableName() {
        return "stock_movements";
    }

    @Override
    protected StockMovement mapRow(ResultSet rs) throws SQLException {
        StockMovement m = new StockMovement();
        m.setId(rs.getInt("id"));
        m.setInventoryId(rs.getInt("inventory_id"));
        m.setChangeQty(rs.getInt("change_qty"));
        m.setReason(getEnum(rs, "reason", MovementReason.class));
        m.setUserId(getInteger(rs, "user_id"));
        m.setNote(rs.getString("note"));
        m.setCreatedAt(rs.getString("created_at"));
        return m;
    }

    public int insert(StockMovement m) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return insert(con, m);
        }
    }

    /** Insert on the caller's connection (for transactions). */
    public int insert(Connection con, StockMovement m) throws SQLException {
        if (m.getCreatedAt() == null) {
            m.setCreatedAt(DateUtil.now());
        }
        int id = executeInsert(con,
                "INSERT INTO stock_movements (inventory_id, change_qty, reason, user_id, note, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                m.getInventoryId(), m.getChangeQty(), m.getReason(), m.getUserId(), m.getNote(), m.getCreatedAt());
        m.setId(id);
        return id;
    }

    public boolean update(StockMovement m) throws SQLException {
        return executeUpdate(
                "UPDATE stock_movements SET inventory_id = ?, change_qty = ?, reason = ?, user_id = ?, note = ? "
                + "WHERE id = ?",
                m.getInventoryId(), m.getChangeQty(), m.getReason(), m.getUserId(), m.getNote(), m.getId()) > 0;
    }

    /** Newest first. */
    public List<StockMovement> findByInventory(int inventoryId) throws SQLException {
        return findList("SELECT * FROM stock_movements WHERE inventory_id = ? ORDER BY id DESC", inventoryId);
    }
}
