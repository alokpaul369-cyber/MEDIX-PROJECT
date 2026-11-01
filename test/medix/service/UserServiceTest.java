package medix.service;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import medix.dao.AreaDao;
import medix.dao.UserDao;
import medix.model.Area;
import medix.model.AuditLogView;
import medix.model.Role;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.Session;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Users screen (disable / enable, add admin), the patient profile (password, search history)
 * and the audit log filters. Real SQLite database in a temporary folder (never data/medix.db).
 */
public class UserServiceTest {

    private static final String PASSWORD = "secret1";

    private static Area dhanmondi;
    private static User patient;
    private static User other;

    private final UserService userService = new UserService();
    private final AuthService authService = new AuthService();

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        dhanmondi = new AreaDao().findByName("Dhanmondi");
        patient = new AuthService().registerPatient("Rahim Uddin", "rahim@test.com", "01712345678", PASSWORD,
                dhanmondi.getId());
        other = new AuthService().registerPatient("Karim Ali", "karim@test.com", "01812345678", PASSWORD,
                dhanmondi.getId());
    }

    @Before
    public void asAdmin() throws Exception {
        Session.login(new UserDao().findById(1), null);
    }

    @Test
    public void anAdminCannotDisableThemself() throws Exception {
        try {
            userService.setActive(1, false);
            fail("the logged-in admin must not lock themself out");
        } catch (ServiceException expected) {
            assertEquals("You cannot disable your own account.", expected.getMessage());
        }
        assertTrue(new UserDao().findById(1).isActive());
    }

    @Test
    public void aDisabledUserCannotLogInUntilEnabled() throws Exception {
        userService.setActive(other.getId(), false);
        assertFalse(new UserDao().findById(other.getId()).isActive());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'USER_DISABLED' AND user_id = 1 "
                + "AND entity_id = " + other.getId()));
        try {
            authService.login("karim@test.com", PASSWORD);
            fail("disabled accounts cannot log in");
        } catch (AuthException expected) {
            assertEquals(AuthService.MSG_DISABLED, expected.getMessage());
        }

        Session.login(new UserDao().findById(1), null);
        userService.setActive(other.getId(), true);
        assertEquals(1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'USER_ENABLED' AND entity_id = " + other.getId()));
        assertEquals(Role.PATIENT, authService.login("karim@test.com", PASSWORD).getRole());
    }

    @Test
    public void adminsCanAddAdminsButPatientsCannot() throws Exception {
        User admin = authService.registerAdmin("Second Admin", "Admin2@Test.com", "01912345678", "admin456");
        assertEquals(Role.ADMIN, admin.getRole());
        assertEquals("emails are stored lower-case", "admin2@test.com", admin.getEmail());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'ADMIN_CREATED' AND user_id = 1 "
                + "AND entity_id = " + admin.getId()));
        assertEquals(Role.ADMIN, authService.login("admin2@test.com", "admin456").getRole());

        Session.login(new UserDao().findById(patient.getId()), null);
        try {
            authService.registerAdmin("Sneaky", "sneaky@test.com", "01912345670", "admin456");
            fail("a patient must not create admins");
        } catch (AuthException expected) {
            assertTrue(expected.getMessage().contains("admin"));
        }
        assertFalse(authService.isEmailRegistered("sneaky@test.com"));
    }

    @Test
    public void usersCanBeFilteredByRoleAndText() throws Exception {
        assertEquals(1, userService.getUsers(Role.PATIENT, "rahim").size());
        assertEquals("Dhanmondi", userService.getUsers(Role.PATIENT, "rahim").get(0).getAreaName());
        assertTrue(userService.getUsers(Role.ADMIN, null).size() >= 1);
        assertEquals(0, userService.getUsers(Role.PHARMACY, "rahim").size());
    }

    @Test
    public void patientsChangeTheirPasswordAndProfile() throws Exception {
        Session.login(new UserDao().findById(patient.getId()), null);
        try {
            userService.changeMyPassword("wrong!", "newpass1", "newpass1");
            fail("the current password must be checked");
        } catch (ServiceException expected) {
            assertEquals("The current password is not correct.", expected.getMessage());
        }
        try {
            userService.changeMyPassword(PASSWORD, "newpass1", "newpass2");
            fail("the confirmation must match");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("do not match"));
        }
        userService.changeMyPassword(PASSWORD, "newpass1", "newpass1");
        authService.login("rahim@test.com", "newpass1");
        userService.changeMyPassword("newpass1", PASSWORD, PASSWORD); // back for the other tests

        Area mirpur = new AreaDao().findByName("Mirpur 10");
        userService.updateMyProfile("Rahim Uddin Ahmed", "017-1234-5679", mirpur.getId());
        User fresh = new UserDao().findById(patient.getId());
        assertEquals("Rahim Uddin Ahmed", fresh.getFullName());
        assertEquals("dashes are stripped", "01712345679", fresh.getPhone());
        assertEquals(Integer.valueOf(mirpur.getId()), fresh.getAreaId());
        assertEquals("the session sees the change", "Rahim Uddin Ahmed", Session.getUser().getFullName());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'PROFILE_UPDATED' AND user_id = " + patient.getId()));
        userService.updateMyProfile("Rahim Uddin", "01712345678", dhanmondi.getId());
    }

    @Test
    public void deleteMySearchHistoryOnlyDeletesMyRows() throws Exception {
        sql("DELETE FROM search_logs");
        for (int i = 0; i < 3; i++) {
            sql("INSERT INTO search_logs (user_id, query, area_id, results_count) VALUES (" + patient.getId()
                    + ", 'napa', " + dhanmondi.getId() + ", 2)");
        }
        sql("INSERT INTO search_logs (user_id, query, area_id, results_count) VALUES (" + other.getId() + ", 'napa', "
                + dhanmondi.getId() + ", 2)");
        Session.login(new UserDao().findById(patient.getId()), null);
        SearchService search = new SearchService();
        assertEquals(3, search.countMySearches());
        assertEquals(3, search.deleteMySearchHistory());
        assertEquals(0, search.countMySearches());
        assertEquals("other people's searches stay", 1, count("SELECT COUNT(*) FROM search_logs"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'SEARCH_HISTORY_DELETED' AND user_id = "
                + patient.getId()));
    }

    @Test
    public void auditLogFiltersByUserActionAndSystem() throws Exception {
        AuditService audit = new AuditService();
        audit.logAs(null, "TEST_SYSTEM_ACTION", null, null, "by a job");
        audit.logAs(1, "TEST_ADMIN_ACTION", "users", 1, "by the admin, with a comma");

        List<AuditLogView> system = audit.search(AuditService.SYSTEM_USER, "TEST_SYSTEM_ACTION", null, null);
        assertEquals(1, system.size());
        assertEquals("System", system.get(0).getUserName());
        assertEquals(0, audit.search(1, "TEST_SYSTEM_ACTION", null, null).size());

        List<AuditLogView> mine = audit.search(1, null, new java.util.Date(), new java.util.Date());
        assertTrue(mine.size() >= 1);
        assertTrue("newest first", mine.get(0).getLog().getId() > mine.get(mine.size() - 1).getLog().getId()
                || mine.size() == 1);
        assertTrue(audit.getActions().contains("TEST_ADMIN_ACTION"));
        assertEquals("\"by the admin, with a comma\"", AuditService.csv("by the admin, with a comma"));
        assertEquals("\"say \"\"hi\"\"\"", AuditService.csv("say \"hi\""));

        Session.login(new UserDao().findById(patient.getId()), null);
        try {
            audit.search(null, null, null, null);
            fail("patients cannot read the audit log");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("admin"));
        }
    }

    // ---------------------------------------------------------------- helpers

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
