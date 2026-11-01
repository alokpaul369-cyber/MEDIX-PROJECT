package medix.service;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.model.NotificationType;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.Role;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.FileStorageUtil;
import medix.util.PasswordUtil;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * Login, logout and sign-up for patients and pharmacies.
 * Forms validate field by field first; this class re-checks the rules that
 * protect the database (duplicates, required values) and throws AuthException.
 */
public class AuthService {

    public static final String MSG_WRONG_LOGIN = "Email or password is incorrect.";
    public static final String MSG_DISABLED = "This account is disabled. Contact the admin.";
    public static final String MSG_PENDING = "Your pharmacy is waiting for admin verification.";
    public static final String MSG_SUSPENDED = "Your pharmacy is suspended. Contact the admin.";

    private final UserDao userDao = new UserDao();
    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final AuditService auditService = new AuditService();
    private final NotificationService notificationService = new NotificationService();

    /**
     * Checks the email and password and the account / pharmacy status.
     * On success the user (and their pharmacy) are stored in Session.
     */
    public User login(String email, String password) throws AuthException, SQLException {
        String normalized = ValidationUtil.normalizeEmail(email);
        User user = ValidationUtil.isBlank(normalized) ? null : userDao.findByEmail(normalized);
        if (user == null || !PasswordUtil.verify(password, user.getSalt(), user.getPasswordHash())) {
            // nobody is proven to be logged in, so no user_id; entity_id points at the account tried
            auditService.logAs(null, AuditService.LOGIN_FAILED, "users",
                    user == null ? null : user.getId(), "Email: " + normalized);
            throw new AuthException(MSG_WRONG_LOGIN);
        }
        if (!user.isActive()) {
            throw new AuthException(MSG_DISABLED);
        }
        Pharmacy pharmacy = null;
        if (user.getRole() == Role.PHARMACY) {
            pharmacy = pharmacyDao.findByOwnerId(user.getId());
            if (pharmacy == null) {
                throw new AuthException("No pharmacy is linked to this account. Contact the admin.");
            }
            checkPharmacyStatus(pharmacy);
        }
        Session.login(user, pharmacy);
        auditService.log(AuditService.LOGIN, "users", user.getId(), user.getRole().name());
        return user;
    }

    public void logout() {
        if (Session.isLoggedIn()) {
            auditService.log(AuditService.LOGOUT, "users", Session.getUserId(), null);
        }
        Session.logout();
    }

    public boolean isEmailRegistered(String email) throws SQLException {
        return userDao.emailExists(ValidationUtil.normalizeEmail(email));
    }

    public boolean isLicenseRegistered(String licenseNo) throws SQLException {
        return pharmacyDao.licenseNoExists(licenseNo.trim());
    }

    /** Creates a PATIENT account and returns it (with its new id). */
    public User registerPatient(String fullName, String email, String phone, String password, int areaId)
            throws AuthException, SQLException {
        User user = newUser(fullName, email, phone, password, Role.PATIENT);
        user.setAreaId(areaId);
        userDao.insert(user);
        auditService.logAs(user.getId(), AuditService.SIGNUP, "users", user.getId(), "Patient account created");
        return user;
    }

    /** Admin only: creates another ADMIN account (Users screen) and returns it. */
    public User registerAdmin(String fullName, String email, String phone, String password)
            throws AuthException, SQLException {
        if (!Session.hasRole(Role.ADMIN)) {
            throw new AuthException("Only an admin can add another admin.");
        }
        User user = newUser(fullName, email, phone, password, Role.ADMIN);
        userDao.insert(user);
        auditService.log("ADMIN_CREATED", "users", user.getId(), user.getFullName() + " (" + user.getEmail() + ")");
        return user;
    }

    /**
     * Creates the PHARMACY user and its pharmacy (status PENDING) in one transaction,
     * copies the license document to data/licenses/pharmacy_&lt;id&gt;_&lt;file name&gt;
     * and notifies every admin. pharmacy must already have name, license number,
     * address, area, coordinates and opening hours filled in.
     */
    public Pharmacy registerPharmacy(String ownerName, String email, String phone, String password,
            Pharmacy pharmacy, File licenseFile) throws AuthException, SQLException, IOException {
        String fileProblem = FileStorageUtil.checkLicenseFile(licenseFile);
        if (fileProblem != null) {
            throw new AuthException(fileProblem);
        }
        if (ValidationUtil.isBlank(pharmacy.getName()) || ValidationUtil.isBlank(pharmacy.getAddress())
                || !ValidationUtil.isValidLicenseNo(pharmacy.getLicenseNo())) {
            throw new AuthException("Pharmacy name, address and a valid license number are required.");
        }
        pharmacy.setLicenseNo(pharmacy.getLicenseNo().trim());
        if (isLicenseRegistered(pharmacy.getLicenseNo())) {
            throw new AuthException("This drug license number is already registered.");
        }
        User owner = newUser(ownerName, email, phone, password, Role.PHARMACY);
        owner.setAreaId(pharmacy.getAreaId());
        pharmacy.setPhone(owner.getPhone());
        pharmacy.setStatus(PharmacyStatus.PENDING);

        String storedPath = null;
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                userDao.insert(con, owner);
                pharmacy.setOwnerId(owner.getId());
                pharmacyDao.insert(con, pharmacy);
                storedPath = FileStorageUtil.copyLicense(licenseFile, pharmacy.getId());
                pharmacyDao.updateLicenseDocPath(con, pharmacy.getId(), storedPath);
                pharmacy.setLicenseDocPath(storedPath);
                con.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                con.rollback();
                FileStorageUtil.deleteQuietly(storedPath);
                throw e;
            }
        }

        auditService.logAs(owner.getId(), AuditService.SIGNUP, "pharmacies", pharmacy.getId(),
                "Pharmacy registered: " + pharmacy.getName() + " (" + pharmacy.getLicenseNo() + ")");
        notificationService.notifyAdmins(NotificationType.VERIFICATION,
                "New pharmacy waiting for verification: " + pharmacy.getName(),
                NotificationService.LINK_PHARMACY, pharmacy.getId());
        return pharmacy;
    }

    private User newUser(String fullName, String email, String phone, String password, Role role)
            throws AuthException, SQLException {
        if (ValidationUtil.isBlank(fullName)) {
            throw new AuthException("Name is required.");
        }
        if (!ValidationUtil.isValidEmail(email)) {
            throw new AuthException("Enter a valid email address.");
        }
        if (!ValidationUtil.isValidBdMobile(phone)) {
            throw new AuthException("Enter a Bangladeshi mobile number like 01712345678.");
        }
        if (!ValidationUtil.isValidPassword(password)) {
            throw new AuthException("The password must be at least "
                    + ValidationUtil.MIN_PASSWORD_LENGTH + " characters.");
        }
        if (isEmailRegistered(email)) {
            throw new AuthException("This email is already registered.");
        }
        String salt = PasswordUtil.generateSalt();
        User u = new User();
        u.setFullName(fullName.trim());
        u.setEmail(ValidationUtil.normalizeEmail(email));
        u.setPhone(ValidationUtil.normalizePhone(phone));
        u.setSalt(salt);
        u.setPasswordHash(PasswordUtil.hash(password, salt));
        u.setRole(role);
        u.setActive(true);
        return u;
    }

    private void checkPharmacyStatus(Pharmacy pharmacy) throws AuthException {
        switch (pharmacy.getStatus()) {
            case VERIFIED:
                return;
            case PENDING:
                throw new AuthException(MSG_PENDING);
            case REJECTED:
                String reason = ValidationUtil.isBlank(pharmacy.getRejectReason())
                        ? "No reason was given." : pharmacy.getRejectReason();
                throw new AuthException("Your pharmacy registration was rejected.\nReason: " + reason);
            case SUSPENDED:
                throw new AuthException(MSG_SUSPENDED);
            default:
                throw new AuthException(MSG_PENDING);
        }
    }
}
