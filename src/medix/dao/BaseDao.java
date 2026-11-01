package medix.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import medix.util.DBConnection;

/**
 * Shared JDBC code for the table DAOs. Every table has "id INTEGER PRIMARY KEY AUTOINCREMENT"
 * as its first column; ids are never set by hand, insert() returns the generated id.
 *
 * Parameters passed to the helpers may be: null, String, Integer/int, Double/double,
 * Long, Boolean (stored as 0/1) or an enum (stored as name()).
 */
public abstract class BaseDao<T> {

    /** Table name, e.g. "areas". */
    protected abstract String tableName();

    /** Builds a model object from the current row. */
    protected abstract T mapRow(ResultSet rs) throws SQLException;

    public T findById(int id) throws SQLException {
        return findOne("SELECT * FROM " + tableName() + " WHERE id = ?", id);
    }

    /** findById on the caller's connection (inside a transaction). */
    public T findById(Connection con, int id) throws SQLException {
        return findOne(con, "SELECT * FROM " + tableName() + " WHERE id = ?", id);
    }

    public List<T> findAll() throws SQLException {
        return findList("SELECT * FROM " + tableName() + " ORDER BY id");
    }

    public boolean delete(int id) throws SQLException {
        return executeUpdate("DELETE FROM " + tableName() + " WHERE id = ?", id) > 0;
    }

    public int count() throws SQLException {
        return queryInt("SELECT COUNT(*) FROM " + tableName());
    }

    /** Runs a query that returns a single number, e.g. SELECT COUNT(*). Returns 0 for no row. */
    protected int queryInt(String sql, Object... params) throws SQLException {
        try (Connection con = DBConnection.getConnection();
                PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Runs an INSERT and returns the new id from getGeneratedKeys(). */
    protected int executeInsert(String sql, Object... params) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return executeInsert(con, sql, params);
        }
    }

    /**
     * Same as above on a connection the caller owns (used inside a transaction:
     * the service opens the connection, calls setAutoCommit(false), commits or rolls back).
     */
    protected int executeInsert(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
            throw new SQLException("Insert into " + tableName() + " returned no generated id");
        }
    }

    /** Runs an UPDATE/DELETE and returns the number of changed rows. */
    protected int executeUpdate(String sql, Object... params) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return executeUpdate(con, sql, params);
        }
    }

    /** Same as above on a connection the caller owns (inside a transaction). */
    protected int executeUpdate(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    protected T findOne(String sql, Object... params) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return findOne(con, sql, params);
        }
    }

    /** Same as above on the caller's connection, so a transaction reads its own changes. */
    protected T findOne(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /** findList on the caller's connection (inside a transaction). */
    protected List<T> findList(Connection con, String sql, Object... params) throws SQLException {
        List<T> list = new ArrayList<T>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    /** queryInt on the caller's connection (inside a transaction). */
    protected int queryInt(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    protected List<T> findList(String sql, Object... params) throws SQLException {
        List<T> list = new ArrayList<T>();
        try (Connection con = DBConnection.getConnection();
                PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    /** Maps one row of a custom query (e.g. a JOIN) to any type. */
    protected interface RowMapper<R> {
        R map(ResultSet rs) throws SQLException;
    }

    /** Runs a custom SELECT (e.g. a JOIN) and maps every row with the mapper. */
    protected <R> List<R> query(String sql, RowMapper<R> mapper, Object... params) throws SQLException {
        List<R> list = new ArrayList<R>();
        try (Connection con = DBConnection.getConnection();
                PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapper.map(rs));
                }
            }
        }
        return list;
    }

    protected static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int index = i + 1;
            if (p == null) {
                ps.setNull(index, Types.NULL);
            } else if (p instanceof Enum) {
                ps.setString(index, ((Enum<?>) p).name());
            } else if (p instanceof Boolean) {
                ps.setInt(index, ((Boolean) p) ? 1 : 0);
            } else if (p instanceof Integer) {
                ps.setInt(index, (Integer) p);
            } else if (p instanceof Long) {
                ps.setLong(index, (Long) p);
            } else if (p instanceof Double) {
                ps.setDouble(index, (Double) p);
            } else {
                ps.setString(index, p.toString());
            }
        }
    }

    /** Reads a nullable INTEGER column. */
    protected static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** Reads a nullable REAL column. */
    protected static Double getDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    protected static boolean getBoolean(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) != 0;
    }

    /** Reads an enum column; returns null if the column is null. */
    protected static <E extends Enum<E>> E getEnum(ResultSet rs, String column, Class<E> type)
            throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }
}
