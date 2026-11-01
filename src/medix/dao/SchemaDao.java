package medix.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import medix.model.TableInfo;
import medix.util.DBConnection;

/**
 * Schema-level access (not tied to one table): runs schema.sql and reads
 * table metadata from SQLite's PRAGMAs.
 */
public class SchemaDao {

    /** Executes each statement on its own, inside one transaction. */
    public void executeScript(List<String> statements) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                for (String sql : statements) {
                    st.execute(sql);
                }
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            }
        }
    }

    /** The CREATE TABLE statement of a table, or null if the table does not exist. */
    public String getTableSql(String table) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return tableSql(con, table);
        }
    }

    /** True if the table has a column with this name (PRAGMA table_info). */
    public boolean hasColumn(String table, String column) throws SQLException {
        try (Connection con = DBConnection.getConnection();
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** PRAGMA user_version: the number of one-time data migrations already applied. */
    public int getUserVersion() throws SQLException {
        try (Connection con = DBConnection.getConnection();
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public long countRows(String table) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return countRows(con, table);
        }
    }

    /** User tables in creation order, with column / PK / foreign-key-index details. */
    public List<TableInfo> findTables() throws SQLException {
        List<TableInfo> tables = new ArrayList<TableInfo>();
        try (Connection con = DBConnection.getConnection()) {
            List<String> names = new ArrayList<String>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type = 'table' "
                    + "AND name NOT LIKE 'sqlite_%' ORDER BY rowid");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            int number = 1;
            for (String name : names) {
                TableInfo info = new TableInfo();
                info.setId(number++);
                info.setName(name);
                readColumns(con, info);
                readForeignKeys(con, info);
                info.setRowCount(countRows(con, name));
                tables.add(info);
            }
        }
        return tables;
    }

    private void readColumns(Connection con, TableInfo info) throws SQLException {
        int columns = 0;
        int pkColumns = 0;
        String pk = null;
        boolean firstIsIntegerIdPk = false;
        try (Statement st = con.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(" + quote(info.getName()) + ")")) {
            while (rs.next()) {
                columns++;
                if (rs.getInt("pk") > 0) {
                    pk = rs.getString("name");
                    pkColumns++;
                }
                if (rs.getInt("cid") == 0) {
                    firstIsIntegerIdPk = "id".equals(rs.getString("name"))
                            && "INTEGER".equalsIgnoreCase(rs.getString("type")) && rs.getInt("pk") == 1;
                }
            }
        }
        info.setColumnCount(columns);
        boolean autoincrement = pk != null && hasAutoincrement(con, info.getName());
        if (autoincrement) {
            pk = pk + " (AUTOINCREMENT)";
        }
        info.setPrimaryKey(pk == null ? "-" : pk);
        info.setIdRuleOk(firstIsIntegerIdPk && pkColumns == 1 && autoincrement);
    }

    private void readForeignKeys(Connection con, TableInfo info) throws SQLException {
        Set<String> fkColumns = new HashSet<String>();
        try (Statement st = con.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA foreign_key_list(" + quote(info.getName()) + ")")) {
            while (rs.next()) {
                fkColumns.add(rs.getString("from"));
            }
        }
        Set<String> indexedFirstColumns = new HashSet<String>();
        List<String> indexes = new ArrayList<String>();
        try (Statement st = con.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA index_list(" + quote(info.getName()) + ")")) {
            while (rs.next()) {
                indexes.add(rs.getString("name"));
            }
        }
        for (String index : indexes) {
            try (Statement st = con.createStatement();
                    ResultSet rs = st.executeQuery("PRAGMA index_info(" + quote(index) + ")")) {
                if (rs.next()) {
                    indexedFirstColumns.add(rs.getString("name"));
                }
            }
        }
        int indexed = 0;
        for (String col : fkColumns) {
            if (indexedFirstColumns.contains(col)) {
                indexed++;
            }
        }
        info.setForeignKeyCount(fkColumns.size());
        info.setIndexedForeignKeyCount(indexed);
    }

    private boolean hasAutoincrement(Connection con, String table) throws SQLException {
        String sql = tableSql(con, table);
        return sql != null && sql.toUpperCase().contains("AUTOINCREMENT");
    }

    private String tableSql(Connection con, String table) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private long countRows(Connection con, String table) throws SQLException {
        try (Statement st = con.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quote(table))) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Table names come from sqlite_master, never from the user; quoting keeps them safe anyway. */
    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
