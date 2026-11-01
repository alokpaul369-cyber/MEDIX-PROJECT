package medix.service;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import medix.dao.AreaDao;
import medix.dao.InventoryDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.model.Area;
import medix.model.Inventory;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.Role;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.Session;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pharmacy stock changes: reserved units are protected, every change writes a stock movement,
 * and a pharmacy only sees its own batches. Real SQLite database in a temporary folder.
 */
public class InventoryServiceTest {

    private static final int SECLO = 5;

    private static Pharmacy mine;
    private static Pharmacy other;
    private static User owner;

    private final InventoryService inventory = new InventoryService();

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        Area dhanmondi = new AreaDao().findByName("Dhanmondi");
        owner = user("inv-owner@test.com", dhanmondi);
        mine = pharmacy(owner, "My Pharmacy", "DL-INV-001", dhanmondi);
        other = pharmacy(user("inv-other@test.com", dhanmondi), "Other Pharmacy", "DL-INV-002", dhanmondi);
    }

    @Before
    public void asOwner() throws Exception {
        sql("DELETE FROM stock_movements");
        sql("DELETE FROM inventory");
        Session.login(owner, mine);
    }

    @Test
    public void reservedUnitsCannotBeSold() throws Exception {
        int id = inventory.addBatch(SECLO, "SC-2610", 10, 7.0, DateUtil.parseDate(DateUtil.todayPlusDays(300)), 2);
        sql("UPDATE inventory SET reserved_qty = 8 WHERE id = " + id); // 8 held for patients, 2 available
        try {
            inventory.sell(id, 3, "Counter sale");
            fail("only 2 units are available");
        } catch (ServiceException expected) {
            // the conditional UPDATE refused it
        }
        assertStock(id, 10, 8);
        inventory.sell(id, 2, "Counter sale");
        assertStock(id, 8, 8);
        try {
            inventory.sell(id, 1, "Counter sale");
            fail("everything left is reserved");
        } catch (ServiceException expected) {
            // nothing available
        }
        assertEquals("one SALE movement only", 1, count("SELECT COUNT(*) FROM stock_movements WHERE reason = 'SALE' "
                + "AND inventory_id = " + id + " AND change_qty = -2"));
    }

    @Test
    public void adjustNeverGoesBelowTheReservedUnits() throws Exception {
        int id = inventory.addBatch(SECLO, "SC-2611", 20, 7.0, DateUtil.parseDate(DateUtil.todayPlusDays(300)), 2);
        sql("UPDATE inventory SET reserved_qty = 5 WHERE id = " + id);
        try {
            inventory.adjust(id, 4, "Counted the shelf");
            fail("5 units are reserved");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("cannot be below 5"));
        }
        inventory.adjust(id, 5, "Counted the shelf");
        assertStock(id, 5, 5);
        assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE reason = 'ADJUST' AND change_qty = -15"));
    }

    @Test
    public void newBatchesNeedAFutureExpiryAndAUniqueBatchNumber() throws Exception {
        try {
            inventory.addBatch(SECLO, "OLD", 10, 7.0, DateUtil.parseDate(DateUtil.today()), 2);
            fail("a batch expiring today cannot be added");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("future"));
        }
        inventory.addBatch(SECLO, "SC-1", 10, 7.0, DateUtil.parseDate(DateUtil.todayPlusDays(100)), 2);
        try {
            inventory.addBatch(SECLO, "sc-1", 5, 7.0, DateUtil.parseDate(DateUtil.todayPlusDays(100)), 2);
            fail("same medicine + batch, ignoring case");
        } catch (ServiceException expected) {
            assertEquals(InventoryService.MSG_DUPLICATE_BATCH, expected.getMessage());
        }
        assertEquals("the ADD movement of the first batch", 1, count("SELECT COUNT(*) FROM stock_movements WHERE reason = 'ADD'"));
    }

    @Test
    public void aPharmacySeesOnlyItsOwnBatches() throws Exception {
        Inventory theirs = new Inventory();
        theirs.setPharmacyId(other.getId());
        theirs.setMedicineId(SECLO);
        theirs.setBatchNo("THEIRS");
        theirs.setQuantity(50);
        theirs.setPrice(7.0);
        theirs.setExpiryDate(DateUtil.todayPlusDays(200));
        int theirId = new InventoryDao().insert(theirs);
        inventory.addBatch(SECLO, "MINE", 10, 7.0, DateUtil.parseDate(DateUtil.todayPlusDays(200)), 2);

        assertEquals(1, inventory.getItems().size());
        assertEquals("MINE", inventory.getItems().get(0).getInventory().getBatchNo());
        try {
            inventory.sell(theirId, 1, "Not mine");
            fail("another pharmacy's batch must not be changed");
        } catch (ServiceException expected) {
            // not found for this pharmacy
        }
        assertStock(theirId, 50, 0);
    }

    @Test
    public void onlyAVerifiedPharmacyChangesStock() throws Exception {
        Session.login(new UserDao().findById(1), null);
        try {
            inventory.addBatch(SECLO, "ADMIN", 10, 7.0, DateUtil.parseDate(DateUtil.todayPlusDays(200)), 2);
            fail("an admin has no stock");
        } catch (ServiceException expected) {
            // not a pharmacy
        }
        sql("UPDATE pharmacies SET status = 'SUSPENDED' WHERE id = " + mine.getId());
        Session.login(owner, mine);
        try {
            inventory.addBatch(SECLO, "SUSP", 10, 7.0, DateUtil.parseDate(DateUtil.todayPlusDays(200)), 2);
            fail("a suspended pharmacy cannot change stock");
        } catch (ServiceException expected) {
            // status read fresh from the database
        } finally {
            sql("UPDATE pharmacies SET status = 'VERIFIED' WHERE id = " + mine.getId());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertStock(int id, int quantity, int reserved) throws Exception {
        Inventory i = new InventoryDao().findById(id);
        assertEquals("quantity", quantity, i.getQuantity());
        assertEquals("reserved", reserved, i.getReservedQty());
    }

    private static User user(String email, Area area) throws Exception {
        User u = new User();
        u.setFullName(email);
        u.setEmail(email);
        u.setPhone("01711111111");
        u.setSalt("c2FsdA==");
        u.setPasswordHash("x");
        u.setRole(Role.PHARMACY);
        u.setAreaId(area.getId());
        u.setActive(true);
        new UserDao().insert(u);
        return u;
    }

    private static Pharmacy pharmacy(User o, String name, String license, Area area) throws Exception {
        Pharmacy p = new Pharmacy();
        p.setOwnerId(o.getId());
        p.setName(name);
        p.setLicenseNo(license);
        p.setAddress("Road 1, Dhanmondi");
        p.setAreaId(area.getId());
        p.setOpenTime("00:00");
        p.setCloseTime("00:00");
        p.setStatus(PharmacyStatus.VERIFIED);
        new PharmacyDao().insert(p);
        return p;
    }

    private static void sql(String s) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(s);
        }
    }

    private static int count(String s) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(s)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
