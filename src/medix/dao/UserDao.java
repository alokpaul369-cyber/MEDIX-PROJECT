package medix.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.Role;
import medix.model.User;
import medix.model.UserDetails;
import medix.util.DBConnection;
import medix.util.DateUtil;

/**
 * Data access for the users table.
 */
public class UserDao extends BaseDao<User> {

    @Override
    protected String tableName() {
        return "users";
    }

    @Override
    protected User mapRow(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(rs.getInt("id"));
        u.setFullName(rs.getString("full_name"));
        u.setEmail(rs.getString("email"));
        u.setPhone(rs.getString("phone"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setSalt(rs.getString("salt"));
        u.setRole(getEnum(rs, "role", Role.class));
        u.setAreaId(getInteger(rs, "area_id"));
        u.setActive(getBoolean(rs, "is_active"));
        u.setCreatedAt(rs.getString("created_at"));
        return u;
    }

    public int insert(User u) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return insert(con, u);
        }
    }

    /** Insert on the caller's connection (for transactions). */
    public int insert(Connection con, User u) throws SQLException {
        if (u.getCreatedAt() == null) {
            u.setCreatedAt(DateUtil.now());
        }
        int id = executeInsert(con,
                "INSERT INTO users (full_name, email, phone, password_hash, salt, role, area_id, "
                + "is_active, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                u.getFullName(), u.getEmail(), u.getPhone(), u.getPasswordHash(), u.getSalt(),
                u.getRole(), u.getAreaId(), u.isActive(), u.getCreatedAt());
        u.setId(id);
        return id;
    }

    public boolean update(User u) throws SQLException {
        return executeUpdate(
                "UPDATE users SET full_name = ?, email = ?, phone = ?, password_hash = ?, salt = ?, "
                + "role = ?, area_id = ?, is_active = ? WHERE id = ?",
                u.getFullName(), u.getEmail(), u.getPhone(), u.getPasswordHash(), u.getSalt(),
                u.getRole(), u.getAreaId(), u.isActive(), u.getId()) > 0;
    }

    /** Case-insensitive lookup by email; null if not found. */
    public User findByEmail(String email) throws SQLException {
        return findOne("SELECT * FROM users WHERE email = ? COLLATE NOCASE", email);
    }

    public List<User> findByRole(Role role) throws SQLException {
        return findList("SELECT * FROM users WHERE role = ? ORDER BY id", role);
    }

    /** Profile edit on the caller's connection (for transactions). */
    public boolean updateNameAndPhone(Connection con, int id, String fullName, String phone) throws SQLException {
        return executeUpdate(con, "UPDATE users SET full_name = ?, phone = ? WHERE id = ?", fullName, phone, id) > 0;
    }

    public boolean emailExists(String email) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM users WHERE email = ? COLLATE NOCASE", email) > 0;
    }

    /** Patient profile edit: name, phone and home area. */
    public boolean updateProfile(int id, String fullName, String phone, Integer areaId) throws SQLException {
        return executeUpdate("UPDATE users SET full_name = ?, phone = ?, area_id = ? WHERE id = ?",
                fullName, phone, areaId, id) > 0;
    }

    public boolean updatePassword(int id, String passwordHash, String salt) throws SQLException {
        return executeUpdate("UPDATE users SET password_hash = ?, salt = ? WHERE id = ?", passwordHash, salt, id) > 0;
    }

    /** Enables / disables the account; false when it already had that state. */
    public boolean setActive(int id, boolean active) throws SQLException {
        return executeUpdate("UPDATE users SET is_active = ? WHERE id = ? AND is_active <> ?", active, id, active) > 0;
    }

    public int countActiveByRole(Role role) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM users WHERE role = ? AND is_active = 1", role);
    }

    /**
     * Users with the role (null = all) whose name or email contains the text (null/empty = all),
     * with their area name, oldest first.
     */
    public List<UserDetails> findDetails(Role role, String text) throws SQLException {
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return query("SELECT u.*, a.name AS area_name FROM users u LEFT JOIN areas a ON a.id = u.area_id "
                + "WHERE (? IS NULL OR u.role = ?) AND (u.full_name LIKE ? OR u.email LIKE ?) ORDER BY u.id",
                rs -> new UserDetails(mapRow(rs), rs.getString("area_name") == null ? "" : rs.getString("area_name")),
                role, role, like, like);
    }
}
