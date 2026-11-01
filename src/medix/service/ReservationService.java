package medix.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import medix.dao.AreaDao;
import medix.dao.InventoryDao;
import medix.dao.MedicineDao;
import medix.dao.PharmacyDao;
import medix.dao.ReservationDao;
import medix.dao.StockMovementDao;
import medix.dao.UserDao;
import medix.engine.AvailabilityCache;
import medix.engine.GeoUtil;
import medix.engine.OpeningHours;
import medix.model.Area;
import medix.model.Inventory;
import medix.model.Medicine;
import medix.model.MovementReason;
import medix.model.NotificationType;
import medix.model.Pharmacy;
import medix.model.PharmacyOffer;
import medix.model.PharmacyStatus;
import medix.model.Reservation;
import medix.model.ReservationStatus;
import medix.model.ReservationView;
import medix.model.Role;
import medix.model.StockMovement;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * Patients hold medicine for a short time before travelling.
 *
 * reserve() and cancel() each run in ONE transaction on a BEGIN IMMEDIATE connection
 * (DBConnection.getTransactionConnection): the write lock is taken first, then availability is
 * re-checked, so two patients reserving at the same moment are served one after the other and
 * can never hold more units than exist. inventory.reserved_qty is also guarded by a conditional
 * UPDATE and by the table's CHECK (reserved_qty &lt;= quantity).
 *
 * stock_movements for holds: RESERVED change_qty = +units put on hold, RELEASED = −units released
 * (the change of reserved_qty; quantity itself does not change).
 */
public class ReservationService {

    public static final int MAX_ACTIVE_PER_PATIENT = 3;
    public static final int MAX_UNITS = 10;
    public static final int[] HOLD_MINUTES = {30, 45, 60};
    public static final int DEFAULT_HOLD_MINUTES = 45;
    public static final int MAX_NOTE_LENGTH = 200;

    private final ReservationDao reservationDao = new ReservationDao();
    private final InventoryDao inventoryDao = new InventoryDao();
    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final MedicineDao medicineDao = new MedicineDao();
    private final AreaDao areaDao = new AreaDao();
    private final UserDao userDao = new UserDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final NotificationService notificationService = new NotificationService();
    private final AuditService auditService = new AuditService();

    // ---------------------------------------------------------------- reading

    /** The pharmacy detail screen: pharmacy, distance from the area, the medicine's valid batches. */
    public PharmacyOffer getOffer(int pharmacyId, int medicineId, int areaId) throws ServiceException, SQLException {
        Pharmacy p = pharmacyDao.findById(pharmacyId);
        if (p == null || p.getStatus() != PharmacyStatus.VERIFIED) {
            throw new ServiceException("This pharmacy is not available on MediX right now.");
        }
        Medicine m = medicineDao.findById(medicineId);
        if (m == null) {
            throw new ServiceException("This medicine is no longer in the catalog.");
        }
        Area from = areaDao.findById(areaId);
        Area own = p.getAreaId() == null ? null : areaDao.findById(p.getAreaId());
        double lat = p.getLatitude() != null ? p.getLatitude() : own == null ? 0 : own.getLatitude();
        double lon = p.getLongitude() != null ? p.getLongitude() : own == null ? 0 : own.getLongitude();
        double km = from == null ? 0 : GeoUtil.distanceKm(from.getLatitude(), from.getLongitude(), lat, lon);
        return new PharmacyOffer(p, own == null ? "" : own.getName(), km, m,
                inventoryDao.findValidBatches(pharmacyId, medicineId), OpeningHours.isOpenNow(p.getOpenTime(), p.getCloseTime()));
    }

    public List<ReservationView> getMyReservations(int patientId) throws SQLException {
        return reservationDao.findViewsByPatient(patientId);
    }

    public ReservationView getView(int reservationId) throws SQLException {
        return reservationDao.findView(reservationId);
    }

    /**
     * FEFO without splitting: the earliest-expiring batch that alone has at least `units`
     * available, or null. Batches must be in expiry order (as findValidBatches returns them).
     */
    public static Inventory pickBatch(List<Inventory> batches, int units) {
        for (Inventory i : batches) {
            if (i.getAvailableQty() >= units) {
                return i;
            }
        }
        return null;
    }

    /** The most units one batch can give (what can be reserved at once). */
    public static int largestBatchAvailable(List<Inventory> batches) {
        int max = 0;
        for (Inventory i : batches) {
            max = Math.max(max, i.getAvailableQty());
        }
        return max;
    }

    /** MX-MMDD-&lt;id padded to 4&gt;, e.g. MX-1101-0042 (date of createdAt, yyyy-MM-dd...). */
    public static String code(int id, String createdAt) {
        return "MX-" + createdAt.substring(5, 7) + createdAt.substring(8, 10) + "-" + String.format("%04d", id);
    }

    /** Minutes until expires_at (negative when already past). */
    public static long minutesLeft(Reservation r) {
        java.util.Date end = DateUtil.parseDateTime(r.getExpiresAt());
        if (end == null) {
            return 0;
        }
        return (long) Math.floor((end.getTime() - System.currentTimeMillis()) / 60000.0);
    }

    // ---------------------------------------------------------------- reserve

    /**
     * Holds `units` of the medicine at the pharmacy for `minutes` (30, 45 or 60).
     * Returns the new reservation (with its MX-MMDD-0000 code).
     */
    public ReservationView reserve(int patientId, int pharmacyId, int medicineId, int units, int minutes, String note)
            throws ServiceException, SQLException {
        checkCaller(patientId);
        if (units < 1 || units > MAX_UNITS) {
            throw new ServiceException("You can reserve 1 to " + MAX_UNITS + " units at a time.");
        }
        if (!isAllowedHold(minutes)) {
            throw new ServiceException("Choose a hold time of 30, 45 or 60 minutes.");
        }
        String cleanNote = ValidationUtil.trimToNull(note);
        if (cleanNote != null && cleanNote.length() > MAX_NOTE_LENGTH) {
            throw new ServiceException("The note is too long (max " + MAX_NOTE_LENGTH + " characters).");
        }
        User patient = userDao.findById(patientId);
        if (patient == null || patient.getRole() != Role.PATIENT || !patient.isActive()) {
            throw new ServiceException("Only an active patient account can reserve medicine.");
        }
        Medicine medicine = medicineDao.findById(medicineId);
        if (medicine == null) {
            throw new ServiceException("This medicine is no longer in the catalog.");
        }
        String medicineName = medicine.getBrandName() + " " + medicine.getStrength();

        Reservation r = new Reservation();
        Pharmacy pharmacy;
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false); // BEGIN IMMEDIATE: from here on we hold the write lock
            try {
                pharmacy = pharmacyDao.findById(con, pharmacyId);
                if (pharmacy == null || pharmacy.getStatus() != PharmacyStatus.VERIFIED) {
                    throw new ServiceException("This pharmacy cannot take reservations right now.");
                }
                if (!OpeningHours.isOpenNow(pharmacy.getOpenTime(), pharmacy.getCloseTime())) {
                    throw new ServiceException(pharmacy.getName() + " is closed now. Reserve when it is open.");
                }
                if (reservationDao.countActiveByPatient(con, patientId) >= MAX_ACTIVE_PER_PATIENT) {
                    throw new ServiceException("You already have " + MAX_ACTIVE_PER_PATIENT
                            + " active reservations. Collect or cancel one first.");
                }
                Reservation same = reservationDao.findActive(con, patientId, pharmacyId, medicineId);
                if (same != null) {
                    throw new ServiceException("You already reserved " + medicineName + " at " + pharmacy.getName()
                            + " (" + same.getCode() + "). Cancel it first to change the quantity.");
                }
                // re-check availability inside the transaction: FEFO batch that alone has enough
                List<Inventory> batches = inventoryDao.findValidBatches(con, pharmacyId, medicineId);
                Inventory batch = pickBatch(batches, units);
                if (batch == null || !inventoryDao.reserve(con, batch.getId(), units)) {
                    int max = largestBatchAvailable(inventoryDao.findValidBatches(con, pharmacyId, medicineId));
                    throw new ServiceException(max == 0 ? "None available now. Someone may have just reserved the last units."
                            : "Only " + max + " available now.");
                }
                r.setCode("NEW-" + UUID.randomUUID()); // unique placeholder; the real code needs the id
                r.setPatientId(patientId);
                r.setPharmacyId(pharmacyId);
                r.setMedicineId(medicineId);
                r.setInventoryId(batch.getId());
                r.setQuantity(units);
                r.setStatus(ReservationStatus.PENDING);
                r.setExpiresAt(DateUtil.nowPlusMinutes(minutes));
                r.setNote(cleanNote);
                reservationDao.insert(con, r);
                r.setCode(code(r.getId(), r.getCreatedAt()));
                reservationDao.updateCode(con, r.getId(), r.getCode());
                movementDao.insert(con, movement(batch.getId(), units, MovementReason.RESERVED, patientId,
                        r.getCode() + " held for " + minutes + " min"));
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }

        AvailabilityCache.invalidateMedicine(medicineId);
        String holdUntil = r.getExpiresAt().substring(11, 16);
        notificationService.notifyUser(pharmacy.getOwnerId(), NotificationType.RESERVATION,
                "New reservation " + r.getCode() + ": " + medicineName + " × " + units + ", hold until " + holdUntil + ".",
                NotificationService.LINK_RESERVATION, r.getId());
        auditService.logAs(patientId, "RESERVATION_CREATED", "reservations", r.getId(),
                r.getCode() + ": " + medicineName + " x " + units + " at " + pharmacy.getName() + " until " + holdUntil);
        return reservationDao.findView(r.getId());
    }

    // ---------------------------------------------------------------- cancel

    /** The patient cancels an active reservation: units go back (reserved_qty −= qty, RELEASED). */
    public void cancel(int reservationId, int patientId) throws ServiceException, SQLException {
        checkCaller(patientId);
        Reservation r;
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                r = reservationDao.findById(con, reservationId);
                if (r == null || r.getPatientId() != patientId) {
                    throw new ServiceException("This reservation was not found.");
                }
                if (!r.getStatus().isActive() || !reservationDao.changeActiveStatus(con, r.getId(), ReservationStatus.CANCELLED)) {
                    throw new ServiceException("This reservation is already " + r.getStatus().toString().toLowerCase() + ".");
                }
                reservationDao.setCancelInfo(con, r.getId(), "PATIENT", null);
                if (r.getInventoryId() != null) {
                    if (!inventoryDao.release(con, r.getInventoryId(), r.getQuantity())) {
                        ErrorLog.log("Reservation " + r.getCode() + ": fewer units were reserved than expected.");
                    }
                    movementDao.insert(con, movement(r.getInventoryId(), -r.getQuantity(), MovementReason.RELEASED,
                            patientId, r.getCode() + " cancelled by the patient"));
                }
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        AvailabilityCache.invalidateMedicine(r.getMedicineId());
        ReservationView v = reservationDao.findView(r.getId());
        notificationService.notifyUser(v.getPharmacyOwnerId(), NotificationType.RESERVATION,
                "Reservation " + r.getCode() + " (" + v.getMedicineShort() + " × " + r.getQuantity()
                + ") was cancelled by the patient. The units are available again.",
                NotificationService.LINK_RESERVATION, r.getId());
        auditService.logAs(patientId, "RESERVATION_CANCELLED", "reservations", r.getId(), r.getCode());
        new TrustScoreService().recalculateQuietly(r.getPharmacyId()); // patient cancels never lower it
    }

    // ---------------------------------------------------------------- expiry (background job)

    /**
     * ReservationExpiryJob: every PENDING / CONFIRMED / READY reservation whose hold ended becomes
     * EXPIRED and its units go back (reserved_qty −= qty, RELEASED movement), one IMMEDIATE
     * transaction per reservation. Patient and pharmacy are notified. Runs without a logged-in user.
     * Returns how many reservations expired.
     */
    public int expireOverdue() throws SQLException {
        int expired = 0;
        for (Reservation overdue : reservationDao.findOverdueActive(DateUtil.now())) {
            boolean done = false;
            try (Connection con = DBConnection.getTransactionConnection()) {
                con.setAutoCommit(false);
                try {
                    // the pharmacy may have picked it up or extended it a moment ago: re-check
                    Reservation r = reservationDao.findById(con, overdue.getId());
                    if (r != null && r.getStatus().isActive() && ReservationService.minutesLeft(r) < 0
                            && DateUtil.now().compareTo(r.getExpiresAt()) > 0
                            && reservationDao.changeActiveStatus(con, r.getId(), ReservationStatus.EXPIRED)) {
                        if (r.getInventoryId() != null) {
                            inventoryDao.release(con, r.getInventoryId(), r.getQuantity());
                            movementDao.insert(con, systemMovement(r.getInventoryId(), -r.getQuantity(),
                                    r.getCode() + " expired (not collected)"));
                        }
                        done = true;
                    }
                    con.commit();
                } catch (SQLException | RuntimeException e) {
                    con.rollback();
                    throw e;
                }
            }
            if (done) {
                expired++;
                afterExpiry(overdue.getId());
            }
        }
        return expired;
    }

    private void afterExpiry(int reservationId) throws SQLException {
        ReservationView v = reservationDao.findView(reservationId);
        Reservation r = v.getReservation();
        String what = v.getMedicineShort() + " × " + r.getQuantity();
        AvailabilityCache.invalidateMedicine(r.getMedicineId());
        notificationService.notifyUser(r.getPatientId(), NotificationType.RESERVATION, "Your reservation " + r.getCode()
                + " (" + what + " at " + v.getPharmacyName() + ") expired at " + r.getExpiresAt().substring(11, 16)
                + ". The units were released.", NotificationService.LINK_RESERVATION, r.getId());
        notificationService.notifyUser(v.getPharmacyOwnerId(), NotificationType.RESERVATION, "Reservation " + r.getCode()
                + " (" + what + ") expired without pickup. " + r.getQuantity() + " unit(s) are available again.",
                NotificationService.LINK_RESERVATION, r.getId());
        auditService.logAs(null, "RESERVATION_EXPIRED", "reservations", r.getId(), r.getCode() + ": " + what);
        new TrustScoreService().recalculateQuietly(r.getPharmacyId());
    }

    private static StockMovement systemMovement(int inventoryId, int change, String note) {
        StockMovement m = new StockMovement();
        m.setInventoryId(inventoryId);
        m.setChangeQty(change);
        m.setReason(MovementReason.RELEASED);
        m.setUserId(null);
        m.setNote(note);
        return m;
    }

    // ---------------------------------------------------------------- helpers

    public static boolean isAllowedHold(int minutes) {
        for (int m : HOLD_MINUTES) {
            if (m == minutes) {
                return true;
            }
        }
        return false;
    }

    /** When someone is logged in, they may only act for themselves. */
    private static void checkCaller(int patientId) throws ServiceException {
        Integer me = Session.getUserId();
        if (me != null && me != patientId) {
            throw new ServiceException("You can only manage your own reservations.");
        }
    }

    private static StockMovement movement(int inventoryId, int change, MovementReason reason, int userId, String note) {
        StockMovement m = new StockMovement();
        m.setInventoryId(inventoryId);
        m.setChangeQty(change);
        m.setReason(reason);
        m.setUserId(userId);
        m.setNote(note);
        return m;
    }
}
