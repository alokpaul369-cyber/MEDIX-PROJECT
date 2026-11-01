package medix.util;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import medix.dao.UserDao;
import medix.model.Role;
import medix.service.AuthService;
import medix.service.DatabaseService;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Demo data loads once, through the services, into a temporary database; and the start-up
 * schema check passes (12 tables follow the ID rule, every foreign key has an index).
 */
public class DemoDataSeederTest {

    private static File dir;

    @BeforeClass
    public static void loadOnce() throws Exception {
        dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        assertTrue(DemoDataSeeder.load(null));
    }

    @Test
    public void schemaCheckPasses() throws Exception {
        DatabaseService db = new DatabaseService();
        List<String> lines = db.checkSchema();
        assertEquals("Schema check: 12/12 tables use id INTEGER PRIMARY KEY AUTOINCREMENT", lines.get(0));
        assertTrue(lines.get(1).matches("Schema check: (\\d+)/\\1 foreign key columns have an index"));
        assertEquals("no problem lines", 2, lines.size());
        assertTrue(db.isSchemaOk());
    }

    @Test
    public void loadsOnlyOnce() throws Exception {
        int users = count("SELECT COUNT(*) FROM users");
        assertFalse(DemoDataSeeder.load(null));
        assertEquals(users, count("SELECT COUNT(*) FROM users"));
    }

    @Test
    public void demoLoginsWork() throws Exception {
        AuthService auth = new AuthService();
        assertEquals(Role.PATIENT, auth.login(DemoDataSeeder.PATIENT_EMAIL, DemoDataSeeder.PASSWORD).getRole());
        assertEquals(Role.PHARMACY, auth.login("pharmacy1@medix.com", DemoDataSeeder.PASSWORD).getRole());
        assertEquals(Role.ADMIN, auth.login("admin@medix.com", "admin123").getRole());
        Session.logout();
        assertEquals(6, count("SELECT COUNT(*) FROM users WHERE role = 'PATIENT'"));
        assertTrue(new UserDao().findByEmail("pharmacy12@medix.com") != null);
    }

    @Test
    public void pharmaciesHaveTheRightStatusesAndLicenses() throws Exception {
        assertEquals(9, count("SELECT COUNT(*) FROM pharmacies WHERE status = 'VERIFIED'"));
        assertEquals(2, count("SELECT COUNT(*) FROM pharmacies WHERE status = 'PENDING'"));
        assertEquals(1, count("SELECT COUNT(*) FROM pharmacies WHERE status = 'SUSPENDED'"));
        assertEquals("one verified over 12 months ago", 1, count("SELECT COUNT(*) FROM pharmacies WHERE status = "
                + "'VERIFIED' AND verified_at < datetime('now', 'localtime', '-12 months')"));
        assertEquals(12, new File(dir, "data/licenses").list().length);
        assertEquals("pharmacies of one area are not on the same point", 0, count("SELECT COUNT(*) FROM pharmacies a "
                + "JOIN pharmacies b ON a.id < b.id AND a.latitude = b.latitude AND a.longitude = b.longitude"));
        assertEquals("one pharmacy has stale stock", 1, count("SELECT COUNT(*) FROM pharmacies WHERE stale_penalty = 5"));
    }

    @Test
    public void stockSearchesAndReservationsLookReal() throws Exception {
        assertEquals("8–20 batches per verified pharmacy", 0, count("SELECT COUNT(*) FROM (SELECT p.id, COUNT(i.id) AS n "
                + "FROM pharmacies p LEFT JOIN inventory i ON i.pharmacy_id = p.id WHERE p.status = 'VERIFIED' "
                + "GROUP BY p.id) WHERE n < 8 OR n > 20"));
        assertTrue(count("SELECT COUNT(*) FROM inventory WHERE expiry_date < date('now', 'localtime')") > 0);
        assertTrue(count("SELECT COUNT(*) FROM inventory WHERE quantity - reserved_qty <= low_stock_threshold") > 0);
        assertEquals("nobody in Uttara Sector 7 stocks Insulet R", 0, count("SELECT COUNT(*) FROM inventory i "
                + "JOIN pharmacies p ON p.id = i.pharmacy_id JOIN areas a ON a.id = p.area_id "
                + "JOIN medicines m ON m.id = i.medicine_id WHERE a.name = 'Uttara Sector 7' AND m.brand_name = 'Insulet R'"));
        int searches = count("SELECT COUNT(*) FROM search_logs");
        assertTrue("about 1,200 searches: " + searches, searches > 900 && searches < 1500);
        assertEquals("Napa is the most searched", "Napa", text("SELECT m.brand_name FROM search_logs s JOIN medicines m "
                + "ON m.id = s.medicine_id GROUP BY m.id ORDER BY COUNT(*) DESC LIMIT 1"));
        assertTrue(count("SELECT COUNT(*) FROM search_logs WHERE results_count = 0") > 0);
        assertEquals("reservations in every status", 6, count("SELECT COUNT(DISTINCT status) FROM reservations"));
        assertEquals("stock never goes negative or below the reserved units", 0,
                count("SELECT COUNT(*) FROM inventory WHERE reserved_qty > quantity OR quantity < 0"));
        assertEquals("reserved units match the active holds", count("SELECT COALESCE(SUM(quantity), 0) FROM reservations "
                + "WHERE status IN ('PENDING', 'CONFIRMED', 'READY')"), count("SELECT SUM(reserved_qty) FROM inventory"));
        assertEquals(3, count("SELECT COUNT(DISTINCT status) FROM reports"));
        assertTrue(count("SELECT COUNT(*) FROM job_runs") > 50);
        assertTrue(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'DEMO_DATA_LOADED'") == 1);
    }

    private static int count(String sql) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
