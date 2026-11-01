package medix.service;

import java.sql.SQLException;
import java.util.List;
import medix.dao.AreaDao;
import medix.dao.UserDao;
import medix.model.NotificationType;
import medix.model.Role;
import medix.model.User;
import medix.model.UserDetails;
import medix.util.PasswordUtil;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * Accounts: the admin Users screen (list, disable / enable) and the logged-in user's own
 * profile (name, phone, area, password).
 */
public class UserService {

    private final UserDao userDao = new UserDao();
    private final AreaDao areaDao = new AreaDao();
    private final AuditService auditService = new AuditService();
    private final NotificationService notificationService = new NotificationService();

    // ---------------------------------------------------------------- admin

    /** Users with the role (null = all) whose name or email contains the text. */
    public List<UserDetails> getUsers(Role role, String text) throws ServiceException, SQLException {
        requireAdmin();
        return userDao.findDetails(role, text);
    }

    /**
     * Disables (cannot log in) or enables an account. An admin cannot disable themself
     * or the last active admin.
     */
    public void setActive(int userId, boolean active) throws ServiceException, SQLException {
        requireAdmin();
        User u = userDao.findById(userId);
        if (u == null) {
            throw new ServiceException("This user no longer exists.");
        }
        if (!active && Session.getUserId() != null && Session.getUserId() == userId) {
            throw new ServiceException("You cannot disable your own account.");
        }
        if (!active && u.getRole() == Role.ADMIN && u.isActive() && userDao.countActiveByRole(Role.ADMIN) <= 1) {
            throw new ServiceException("The last active admin cannot be disabled.");
        }
        if (!userDao.setActive(userId, active)) {
            throw new ServiceException(u.getFullName() + " is already " + (active ? "enabled." : "disabled."));
        }
        if (active) {
            notificationService.notifyUser(userId, NotificationType.INFO, "Your account was enabled again by the admin.");
        }
        auditService.log(active ? "USER_ENABLED" : "USER_DISABLED", "users", userId,
                u.getFullName() + " (" + u.getEmail() + ", " + u.getRole().name() + ")");
    }

    // ---------------------------------------------------------------- own profile

    /** The logged-in user, read fresh from the database. */
    public User getMe() throws ServiceException, SQLException {
        User u = userDao.findById(requireLogin());
        if (u == null) {
            throw new ServiceException("Your account no longer exists.");
        }
        return u;
    }

    /** Saves name, phone and home area of the logged-in user (Session is refreshed). */
    public void updateMyProfile(String fullName, String phone, Integer areaId) throws ServiceException, SQLException {
        int me = requireLogin();
        String name = ValidationUtil.trimToNull(fullName);
        if (name == null) {
            throw new ServiceException("Name is required.");
        }
        if (name.length() > 80) {
            throw new ServiceException("Please keep the name under 80 characters.");
        }
        if (!ValidationUtil.isValidBdMobile(phone)) {
            throw new ServiceException("Enter a Bangladeshi mobile number like 01712345678.");
        }
        if (areaId == null || areaDao.findById(areaId) == null) {
            throw new ServiceException("Choose your area.");
        }
        userDao.updateProfile(me, name, ValidationUtil.normalizePhone(phone), areaId);
        refreshSession(me);
        auditService.log("PROFILE_UPDATED", "users", me, name);
    }

    /** Changes the logged-in user's password after checking the current one. */
    public void changeMyPassword(String current, String newPassword, String confirm) throws ServiceException, SQLException {
        User u = getMe();
        if (current == null || current.isEmpty() || !PasswordUtil.verify(current, u.getSalt(), u.getPasswordHash())) {
            throw new ServiceException("The current password is not correct.");
        }
        if (!ValidationUtil.isValidPassword(newPassword)) {
            throw new ServiceException("The new password must be at least " + ValidationUtil.MIN_PASSWORD_LENGTH
                    + " characters.");
        }
        if (!newPassword.equals(confirm)) {
            throw new ServiceException("The new passwords do not match.");
        }
        if (newPassword.equals(current)) {
            throw new ServiceException("The new password is the same as the current one.");
        }
        String salt = PasswordUtil.generateSalt();
        userDao.updatePassword(u.getId(), PasswordUtil.hash(newPassword, salt), salt);
        refreshSession(u.getId());
        auditService.log("PASSWORD_CHANGED", "users", u.getId(), null);
    }

    private void refreshSession(int userId) throws SQLException {
        User fresh = userDao.findById(userId);
        if (fresh != null) {
            Session.login(fresh, Session.getPharmacy());
        }
    }

    private static int requireLogin() throws ServiceException {
        Integer me = Session.getUserId();
        if (me == null) {
            throw new ServiceException("Please log in first.");
        }
        return me;
    }

    private static void requireAdmin() throws ServiceException {
        if (!Session.hasRole(Role.ADMIN)) {
            throw new ServiceException("Only an admin can do this.");
        }
    }
}
