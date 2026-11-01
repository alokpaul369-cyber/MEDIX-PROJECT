package medix.service;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import medix.dao.AreaDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.engine.AvailabilityCache;
import medix.engine.GeoUtil;
import medix.model.Area;
import medix.model.NotificationType;
import medix.model.Pharmacy;
import medix.model.PharmacyDetails;
import medix.model.Role;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.FileStorageUtil;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * The logged-in pharmacy's own profile.
 */
public class PharmacyService {

    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final UserDao userDao = new UserDao();
    private final AreaDao areaDao = new AreaDao();
    private final AreaService areaService = new AreaService();
    private final AuditService auditService = new AuditService();
    private final NotificationService notificationService = new NotificationService();

    /** The logged-in owner's pharmacy with owner and area name (fresh from the database). */
    public PharmacyDetails getMyPharmacy() throws ServiceException, SQLException {
        if (!Session.hasRole(Role.PHARMACY) || Session.getPharmacy() == null) {
            throw new ServiceException("Only a pharmacy account can open this screen.");
        }
        Pharmacy p = pharmacyDao.findById(Session.getPharmacy().getId());
        if (p == null) {
            throw new ServiceException("Your pharmacy no longer exists.");
        }
        Area area = p.getAreaId() == null ? null : areaDao.findById(p.getAreaId());
        return new PharmacyDetails(p, userDao.findById(p.getOwnerId()), area == null ? "" : area.getName(),
                VerificationService.isReverificationDue(p));
    }

    public void openLicense() throws ServiceException, SQLException, IOException {
        Pharmacy p = getMyPharmacy().getPharmacy();
        if (ValidationUtil.isBlank(p.getLicenseDocPath()) || !FileStorageUtil.resolve(p.getLicenseDocPath()).isFile()) {
            throw new ServiceException("The license file is missing. Upload it again with Browse...");
        }
        FileStorageUtil.open(p.getLicenseDocPath());
    }

    /**
     * Saves the editable fields in one transaction (users.full_name / phone and the pharmacy row).
     * openTime/closeTime are HH:mm. A new license file (may be null) is copied to data/licenses and
     * the admins are notified. Returns true when a new license was uploaded.
     */
    public boolean updateProfile(String pharmacyName, String ownerName, String phone, String openTime,
            String closeTime, String address, double lat, double lon, File newLicense)
            throws ServiceException, SQLException, IOException {
        PharmacyDetails current = getMyPharmacy();
        Pharmacy p = current.getPharmacy();
        if (ValidationUtil.isBlank(pharmacyName) || ValidationUtil.isBlank(ownerName) || ValidationUtil.isBlank(address)) {
            throw new ServiceException("Pharmacy name, owner name and address are required.");
        }
        if (!ValidationUtil.isValidBdMobile(phone)) {
            throw new ServiceException("Enter a Bangladeshi mobile number like 01712345678.");
        }
        if (!GeoUtil.isInsideBangladesh(lat, lon)) {
            throw new ServiceException("The location must be inside Bangladesh.");
        }
        if (newLicense != null) {
            String problem = FileStorageUtil.checkLicenseFile(newLicense);
            if (problem != null) {
                throw new ServiceException(problem);
            }
        }
        Area area = areaService.findAreaInText(address);
        if (area == null) {
            area = areaService.findNearestArea(lat, lon);
        }
        p.setName(pharmacyName.trim());
        p.setPhone(ValidationUtil.normalizePhone(phone));
        p.setOpenTime(openTime);
        p.setCloseTime(closeTime);
        p.setAddress(address.trim());
        p.setAreaId(area == null ? null : area.getId());
        p.setLatitude(lat);
        p.setLongitude(lon);

        String storedPath = null;
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                userDao.updateNameAndPhone(con, p.getOwnerId(), ownerName.trim(), p.getPhone());
                pharmacyDao.updateProfile(con, p);
                if (newLicense != null) {
                    storedPath = FileStorageUtil.copyLicense(newLicense, p.getId());
                    pharmacyDao.updateLicenseDocPath(con, p.getId(), storedPath);
                    p.setLicenseDocPath(storedPath);
                }
                con.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                con.rollback();
                // only delete a copy that is not the file the pharmacy already had
                if (storedPath != null && !storedPath.equals(current.getPharmacy().getLicenseDocPath())) {
                    FileStorageUtil.deleteQuietly(storedPath);
                }
                throw e;
            }
        }

        User owner = Session.getUser();
        if (owner != null) {
            owner.setFullName(ownerName.trim());
            owner.setPhone(p.getPhone());
        }
        Session.setPharmacy(p);
        AvailabilityCache.clear(); // location, hours or name changed
        auditService.log("PHARMACY_PROFILE_UPDATED", "pharmacies", p.getId(), p.getName() + ", " + p.getAddress());
        if (newLicense != null) {
            auditService.log("LICENSE_UPLOADED", "pharmacies", p.getId(), storedPath);
            notificationService.notifyAdmins(NotificationType.VERIFICATION,
                    p.getName() + " uploaded a new license document.", NotificationService.LINK_PHARMACY, p.getId());
        }
        return newLicense != null;
    }

    /** "Verified on 01 Nov 2026 · Trust score 80" style line for the profile screen. */
    public static String statusLine(Pharmacy p) {
        String s = "Status: " + p.getStatus();
        if (p.getVerifiedAt() != null) {
            s += " on " + DateUtil.toDisplayDate(p.getVerifiedAt());
        }
        return s + "  ·  Trust score " + p.getTrustScore();
    }
}
