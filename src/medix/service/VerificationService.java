package medix.service;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import medix.dao.AreaDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.engine.AvailabilityCache;
import medix.model.Area;
import medix.model.NotificationType;
import medix.model.Pharmacy;
import medix.model.PharmacyDetails;
import medix.model.PharmacyStatus;
import medix.model.Role;
import medix.model.User;
import medix.util.DateUtil;
import medix.util.FileStorageUtil;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * Admin verification of pharmacy licenses.
 *
 * Allowed changes:
 * <ul>
 *   <li>Approve: PENDING / REJECTED → VERIFIED, or VERIFIED again when re-verification is due
 *       (sets verified_by, verified_at, clears reject_reason).</li>
 *   <li>Reject: PENDING → REJECTED (reason required).</li>
 *   <li>Suspend: VERIFIED → SUSPENDED (reason required).</li>
 *   <li>Reactivate: SUSPENDED → VERIFIED.</li>
 * </ul>
 * Every change notifies the owner and is written to audit_logs.
 */
public class VerificationService {

    /** A VERIFIED pharmacy must be re-verified after this many months (risk plan). */
    public static final int REVERIFY_MONTHS = 12;
    public static final int MIN_REASON_LENGTH = 5;

    public static final String MSG_APPROVED = "Your pharmacy is verified. You can now add your inventory.";
    private static final String MSG_CHANGED = "This pharmacy was changed by someone else. The list was refreshed.";

    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final UserDao userDao = new UserDao();
    private final AreaDao areaDao = new AreaDao();
    private final NotificationService notificationService = new NotificationService();
    private final AuditService auditService = new AuditService();

    /** status null = all statuses; text matches name or license number. */
    public List<PharmacyDetails> getPharmacies(PharmacyStatus status, String text) throws SQLException {
        Map<Integer, String> areaNames = new HashMap<Integer, String>();
        for (Area a : areaDao.findAll()) {
            areaNames.put(a.getId(), a.getName());
        }
        Map<Integer, User> owners = new HashMap<Integer, User>();
        for (User u : userDao.findByRole(Role.PHARMACY)) {
            owners.put(u.getId(), u);
        }
        List<PharmacyDetails> list = new ArrayList<PharmacyDetails>();
        for (Pharmacy p : pharmacyDao.search(status, text)) {
            String area = p.getAreaId() == null ? null : areaNames.get(p.getAreaId());
            list.add(new PharmacyDetails(p, owners.get(p.getOwnerId()), area == null ? "" : area,
                    isReverificationDue(p)));
        }
        return list;
    }

    public PharmacyDetails getDetails(int pharmacyId) throws SQLException {
        Pharmacy p = pharmacyDao.findById(pharmacyId);
        if (p == null) {
            return null;
        }
        Area area = p.getAreaId() == null ? null : areaDao.findById(p.getAreaId());
        return new PharmacyDetails(p, userDao.findById(p.getOwnerId()),
                area == null ? "" : area.getName(), isReverificationDue(p));
    }

    public int countByStatus(PharmacyStatus status) throws SQLException {
        return pharmacyDao.countByStatus(status);
    }

    public static boolean isReverificationDue(Pharmacy p) {
        return p.getStatus() == PharmacyStatus.VERIFIED
                && DateUtil.isOlderThanMonths(p.getVerifiedAt(), REVERIFY_MONTHS);
    }

    public static boolean canApprove(Pharmacy p) {
        return p.getStatus() == PharmacyStatus.PENDING || p.getStatus() == PharmacyStatus.REJECTED
                || isReverificationDue(p);
    }

    public static boolean canReject(Pharmacy p) {
        return p.getStatus() == PharmacyStatus.PENDING;
    }

    public static boolean canSuspend(Pharmacy p) {
        return p.getStatus() == PharmacyStatus.VERIFIED;
    }

    public static boolean canReactivate(Pharmacy p) {
        return p.getStatus() == PharmacyStatus.SUSPENDED;
    }

    /**
     * Opens the license document with the system viewer and logs that the admin viewed it.
     * Throws ServiceException("The license file is missing.") if it is gone.
     */
    public void openLicense(Pharmacy p) throws ServiceException, IOException {
        requireAdmin();
        if (ValidationUtil.isBlank(p.getLicenseDocPath()) || !FileStorageUtil.resolve(p.getLicenseDocPath()).isFile()) {
            throw new ServiceException("The license file is missing.");
        }
        FileStorageUtil.open(p.getLicenseDocPath());
        auditService.log("LICENSE_VIEWED", "pharmacies", p.getId(), p.getLicenseDocPath());
    }

    public void approve(int pharmacyId) throws ServiceException, SQLException {
        requireAdmin();
        Pharmacy p = load(pharmacyId);
        if (!canApprove(p)) {
            throw new ServiceException("A " + p.getStatus() + " pharmacy cannot be approved.");
        }
        boolean reverify = p.getStatus() == PharmacyStatus.VERIFIED;
        change(p, PharmacyStatus.VERIFIED, Session.getUserId(), DateUtil.now(), null);
        notifyOwner(p, reverify ? "Your pharmacy license was re-verified. Thank you." : MSG_APPROVED);
        auditService.log(reverify ? "PHARMACY_REVERIFIED" : "PHARMACY_APPROVED", "pharmacies", p.getId(),
                p.getName() + " (" + p.getLicenseNo() + ")");
    }

    public void reject(int pharmacyId, String reason) throws ServiceException, SQLException {
        requireAdmin();
        String why = requireReason(reason);
        Pharmacy p = load(pharmacyId);
        if (!canReject(p)) {
            throw new ServiceException("Only a pending pharmacy can be rejected.");
        }
        change(p, PharmacyStatus.REJECTED, p.getVerifiedBy(), p.getVerifiedAt(), why);
        notifyOwner(p, "Your pharmacy registration was rejected. Reason: " + why);
        auditService.log("PHARMACY_REJECTED", "pharmacies", p.getId(), why);
    }

    public void suspend(int pharmacyId, String reason) throws ServiceException, SQLException {
        requireAdmin();
        String why = requireReason(reason);
        Pharmacy p = load(pharmacyId);
        if (!canSuspend(p)) {
            throw new ServiceException("Only a verified pharmacy can be suspended.");
        }
        change(p, PharmacyStatus.SUSPENDED, p.getVerifiedBy(), p.getVerifiedAt(), why);
        notifyOwner(p, "Your pharmacy is suspended and hidden from patients. Reason: " + why);
        auditService.log("PHARMACY_SUSPENDED", "pharmacies", p.getId(), why);
    }

    public void reactivate(int pharmacyId) throws ServiceException, SQLException {
        requireAdmin();
        Pharmacy p = load(pharmacyId);
        if (!canReactivate(p)) {
            throw new ServiceException("Only a suspended pharmacy can be reactivated.");
        }
        change(p, PharmacyStatus.VERIFIED, p.getVerifiedBy(), p.getVerifiedAt(), null);
        notifyOwner(p, "Your pharmacy is active again. Patients can see it now.");
        auditService.log("PHARMACY_REACTIVATED", "pharmacies", p.getId(), p.getName());
    }

    /** Changes the status; search results are cleared because the pharmacy appears or disappears. */
    private void change(Pharmacy p, PharmacyStatus to, Integer verifiedBy, String verifiedAt, String reason)
            throws ServiceException, SQLException {
        if (!pharmacyDao.updateStatus(p.getId(), p.getStatus(), to, verifiedBy, verifiedAt, reason)) {
            throw new ServiceException(MSG_CHANGED);
        }
        // the pharmacy appears in or disappears from patient searches
        AvailabilityCache.clear();
    }

    private void notifyOwner(Pharmacy p, String message) throws SQLException {
        notificationService.notifyUser(p.getOwnerId(), NotificationType.VERIFICATION, message,
                NotificationService.LINK_PHARMACY, p.getId());
    }

    private Pharmacy load(int pharmacyId) throws ServiceException, SQLException {
        Pharmacy p = pharmacyDao.findById(pharmacyId);
        if (p == null) {
            throw new ServiceException("This pharmacy no longer exists.");
        }
        return p;
    }

    private static String requireReason(String reason) throws ServiceException {
        if (reason == null || reason.trim().length() < MIN_REASON_LENGTH) {
            throw new ServiceException("Write the reason (at least " + MIN_REASON_LENGTH
                    + " characters). The owner will see it.");
        }
        return reason.trim();
    }

    private static void requireAdmin() throws ServiceException {
        if (!Session.hasRole(Role.ADMIN)) {
            throw new ServiceException("Only an admin can do this.");
        }
    }
}
