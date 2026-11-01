package medix.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.sqlite.SQLiteConfig;

/**
 * Opens connections to data/medix.db. Every connection has foreign keys turned on.
 * Callers close the connection with try-with-resources.
 */
public final class DBConnection {

    private static final String DRIVER = "org.sqlite.JDBC";
    private static final int BUSY_TIMEOUT_MILLIS = 5000;

    private DBConnection() {
    }

    public static Connection getConnection() throws SQLException {
        loadDriver();
        Connection con = DriverManager.getConnection(url());
        try (Statement st = con.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
        } catch (SQLException e) {
            con.close();
            throw e;
        }
        return con;
    }

    /**
     * A connection whose transactions start with BEGIN IMMEDIATE: the write lock is taken when
     * the transaction begins (setAutoCommit(false)), not at the first write. Use it when a
     * transaction READS something and then writes based on it (e.g. "are 4 units still
     * available? then reserve them"), so a second transaction waits (busy timeout 5 s) until
     * the first one commits and then sees the new numbers.
     */
    public static Connection getTransactionConnection() throws SQLException {
        loadDriver();
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return DriverManager.getConnection(url(), config.toProperties());
    }

    private static String url() {
        return "jdbc:sqlite:" + AppPaths.getDatabaseFile().getAbsolutePath();
    }

    private static void loadDriver() throws SQLException {
        try {
            Class.forName(DRIVER);
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver not found. Is lib/sqlite-jdbc on the classpath?", e);
        }
    }
}
