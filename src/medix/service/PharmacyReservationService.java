package medix.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import medix.dao.InventoryDao;
import medix.dao.PharmacyDao;
import medix.dao.ReservationDao;
import medix.dao.StockMovementDao;
import medix.engine.AvailabilityCache;
import medix.model.CancelReason;
import medix.model.MovementReason;
import medix.model.NotificationType;
import medix.model.Pharmacy;
import medix.model.Reservation;
import medix.model.ReservationStatus;
import medix.model.ReservationView;
import medix.model.Role;
import medix.model.StockMovement;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * The logged-in pharmacy handles its reservations:
 * PENDING → CONFIRMED → READY → COMPLETED (picked up), an optional one-time 15-minute
 * extension, or a cancel with a reason.
 *
 * Every change is ONE IMMEDIATE transaction (DBConnection.getTransactionConnection) that only
 * moves the status from the expected step(s) (so two screens cannot both act), sets updated_at,
 * and for pickup / cancel updates inventory + stock_movements. After commit: patient notification,
 * audit log, AvailabilityCache.invalidateMedicine and a trust score recalculation.
 */
public class PharmacyReservationService {

    public static final int EXTEND_MINUTES = 15;

    /** cmbFilter choices. */
    public enum Filter {
        ACTIVE("Active", ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.READY),
        COMPLETED("Completed", ReservationStatus.COMPLETED),
        CANCELLED("Cancelled", ReservationStatus.CANCELLED),
        EXPIRED("Expired", ReservationStatus.EXPIRED),
        ALL("All");

        private final String label;
        private final List<ReservationStatus> statuses;

        Filter(String label, ReservationStatus... statuses) {
            this.label = label;
            this.statuses = Collections.unmodifiableList(Arrays.asList(statuses));
        }

        /** Empty = every status. */
        public List<ReservationStatus> getStatuses() {
            return statuses;
        }

        public boolean includes(ReservationStatus s) {
            return statuses.isEmpty() || statuses.contains(s);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Numbers for the header and the bottom stats. */
    public static final class Stats {
        public final int active;
        public final int toConfirm;
        public final int completedToday;
        public final int expiredToday;
        public final int cancelledByPharmacyToday;
        public final int trustScore;
        public final int trustLastWeek;

        Stats(int active, int toConfirm, int completedToday, int expiredToday, int cancelledByPharmacyToday,
                int trustScore, int trustLastWeek) {
            this.active = active;
            this.toConfirm = toConfirm;
            this.completedToday = completedToday;
            this.expiredToday = expiredToday;
            this.cancelledByPharmacyToday = cancelledByPharmacyToday;
            this.trustScore = trustScore;
            this.trustLastWeek = trustLastWeek;
        }
    }

    private final ReservationDao reservationDao = new ReservationDao();
    private final InventoryDao inventoryDao = new InventoryDao();
    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final NotificationService notificationService = new NotificationService();
    private final AuditService auditService = new AuditService();
    private final TrustScoreService trustScoreService = new TrustScoreService();

    // ---------------------------------------------------------------- reading

    public List<ReservationView> getReservations(Filter filter) throws ServiceException, SQLException {
        return reservationDao.findViewsByPharmacy(myPharmacy().getId(), filter.getStatuses());
    }

    /**
     * Finds one of this pharmacy's reservations by code; "1101-0042" and "mx-1101-0042" work too.
     */
    public Reservation findByCode(String code) throws ServiceException, SQLException {
        String c = code == null ? "" : code.trim().toUpperCase();
        if (c.isEmpty()) {
            throw new ServiceException("Type the reservation code, e.g. MX-1101-0042.");
        }
        if (!c.startsWith("MX-")) {
            c = "MX-" + c;
        }
        Reservation r = reservationDao.findByCodeAtPharmacy(c, myPharmacy().getId());
        if (r == null) {
            throw new ServiceException("No reservation " + c + " at your pharmacy.");
        }
        return r;
    }

    public Stats getStats() throws ServiceException, SQLException {
        int id = myPharmacy().getId();
        String today = DateUtil.today() + " 00:00:00";
        String tomorrow = DateUtil.todayPlusDays(1) + " 00:00:00";
        return new Stats(
                reservationDao.countWithStatus(id, ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.READY),
                reservationDao.countWithStatus(id, ReservationStatus.PENDING),
                reservationDao.countChanged(id, ReservationStatus.COMPLETED, null, null, today, tomorrow),
                reservationDao.countChanged(id, ReservationStatus.EXPIRED, null, null, today, tomorrow),
                reservationDao.countChanged(id, ReservationStatus.CANCELLED, "PHARMACY", null, today, tomorrow),
                pharmacyDao.findById(id).getTrustScore(),
                trustScoreService.scoreLastWeek(id));
    }

    /** The patient's phone is shown in full only once the pharmacy has confirmed (privacy). */
    public static boolean showFullPhone(ReservationStatus s) {
        return s == ReservationStatus.CONFIRMED || s == ReservationStatus.READY || s == ReservationStatus.COMPLETED;
    }

    public static boolean canConfirm(Reservation r) {
        return r.getStatus() == ReservationStatus.PENDING;
    }

    public static boolean canMarkReady(Reservation r) {
        return r.getStatus() == ReservationStatus.CONFIRMED;
    }

    public static boolean canPickUp(Reservation r) {
        return r.getStatus() == ReservationStatus.CONFIRMED || r.getStatus() == ReservationStatus.READY;
    }

    public static boolean canExtend(Reservation r) {
        return r.getStatus().isActive() && !r.isExtended() && ReservationService.minutesLeft(r) >= 0;
    }

    public static boolean canCancel(Reservation r) {
        return r.getStatus().isActive();
    }

    // ---------------------------------------------------------------- actions

    public void confirm(int reservationId) throws ServiceException, SQLException {
        Changed c = changeStatus(reservationId, Arrays.asList(ReservationStatus.PENDING), ReservationStatus.CONFIRMED,
                "Only a pending reservation can be confirmed.");
        after(c, "RESERVATION_CONFIRMED", c.pharmacy.getName() + " confirmed " + c.r.getCode() + ".");
    }

    public void markReady(int reservationId) throws ServiceException, SQLException {
        Changed c = changeStatus(reservationId, Arrays.asList(ReservationStatus.CONFIRMED), ReservationStatus.READY,
                "Only a confirmed reservation can be marked ready.");
        after(c, "RESERVATION_READY", "Your medicine is ready: " + c.view.getMedicineShort() + " × "
                + c.r.getQuantity() + " at " + c.pharmacy.getName() + " (" + c.r.getCode() + "). Please collect it by "
                + c.r.getExpiresAt().substring(11, 16) + ".");
    }

    /**
     * The patient showed the code and took the medicine: CONFIRMED/READY → COMPLETED and, in the
     * same transaction, quantity −= qty and reserved_qty −= qty on the batch (PICKED_UP movement).
     */
    public void pickUp(int reservationId) throws ServiceException, SQLException {
        Pharmacy pharmacy = myPharmacy();
        Reservation r;
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                r = loadOwn(con, reservationId, pharmacy);
                if (!canPickUp(r)) {
                    throw new ServiceException("Only a confirmed or ready reservation can be picked up (this one is "
                            + r.getStatus().toString().toLowerCase() + ").");
                }
                if (r.getInventoryId() == null) {
                    throw new ServiceException("The batch of this reservation was deleted. Cancel the reservation instead.");
                }
                if (!reservationDao.changeStatus(con, r.getId(),
                        Arrays.asList(ReservationStatus.CONFIRMED, ReservationStatus.READY), ReservationStatus.COMPLETED)) {
                    throw new ServiceException(changedMeanwhile());
                }
                if (!inventoryDao.pickUp(con, r.getInventoryId(), r.getQuantity())) {
                    throw new ServiceException("The stock numbers of this batch do not match the reservation. "
                            + "Check the batch in Inventory.");
                }
                movementDao.insert(con, movement(r.getInventoryId(), -r.getQuantity(), MovementReason.PICKED_UP,
                        r.getCode() + " picked up"));
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        Changed c = new Changed(r, pharmacy, reservationDao.findView(r.getId()));
        after(c, "RESERVATION_PICKED_UP", "Thank you! " + r.getCode() + " was picked up at " + pharmacy.getName() + ".");
        new InventoryService().notifyIfLowStock(r.getInventoryId());
    }

    /** Adds 15 minutes to the hold, once per reservation, while it is still running. */
    public void extend(int reservationId) throws ServiceException, SQLException {
        Pharmacy pharmacy = myPharmacy();
        Reservation r;
        String newExpiry;
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                r = loadOwn(con, reservationId, pharmacy);
                if (!r.getStatus().isActive()) {
                    throw new ServiceException("Only an active reservation can be extended.");
                }
                if (r.isExtended()) {
                    throw new ServiceException(r.getCode() + " was already extended once.");
                }
                if (ReservationService.minutesLeft(r) < 0) {
                    throw new ServiceException("The hold of " + r.getCode() + " already ended at "
                            + r.getExpiresAt().substring(11, 16) + ".");
                }
                Date end = DateUtil.parseDateTime(r.getExpiresAt());
                newExpiry = DateUtil.formatDateTime(new Date(end.getTime() + EXTEND_MINUTES * 60000L));
                if (!reservationDao.extendOnce(con, r.getId(), newExpiry)) {
                    throw new ServiceException(changedMeanwhile());
                }
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        Changed c = new Changed(r, pharmacy, reservationDao.findView(r.getId()));
        after(c, "RESERVATION_EXTENDED", pharmacy.getName() + " extended your reservation " + r.getCode() + " by "
                + EXTEND_MINUTES + " minutes. New hold until " + newExpiry.substring(11, 16) + ".");
    }

    /**
     * Cancels an active reservation with a reason; the units go back (reserved_qty −= qty,
     * RELEASED). "Out of stock" lowers the trust score.
     */
    public void cancel(int reservationId, CancelReason reason, String details) throws ServiceException, SQLException {
        if (reason == null) {
            throw new ServiceException("Choose why the reservation is cancelled.");
        }
        String extra = ValidationUtil.trimToNull(details);
        if (reason == CancelReason.OTHER && extra == null) {
            throw new ServiceException("Write the reason for the patient.");
        }
        Pharmacy pharmacy = myPharmacy();
        Reservation r;
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                r = loadOwn(con, reservationId, pharmacy);
                if (!canCancel(r) || !reservationDao.changeStatus(con, r.getId(), Filter.ACTIVE.getStatuses(),
                        ReservationStatus.CANCELLED)) {
                    throw new ServiceException("This reservation is already " + r.getStatus().toString().toLowerCase() + ".");
                }
                reservationDao.setCancelInfo(con, r.getId(), "PHARMACY", reason.name() + (extra == null ? "" : ": " + extra));
                if (r.getInventoryId() != null) {
                    inventoryDao.release(con, r.getInventoryId(), r.getQuantity());
                    movementDao.insert(con, movement(r.getInventoryId(), -r.getQuantity(), MovementReason.RELEASED,
                            r.getCode() + " cancelled by the pharmacy: " + reason));
                }
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        Changed c = new Changed(r, pharmacy, reservationDao.findView(r.getId()));
        after(c, "RESERVATION_CANCELLED_BY_PHARMACY", pharmacy.getName() + " cancelled your reservation " + r.getCode()
                + ": " + reason + (extra == null ? "" : " – " + extra) + ". Search again to find another pharmacy.");
    }

    // ---------------------------------------------------------------- helpers

    /** Result of a committed change, for the after-commit steps. */
    private static final class Changed {
        final Reservation r;
        final Pharmacy pharmacy;
        final ReservationView view;

        Changed(Reservation r, Pharmacy pharmacy, ReservationView view) {
            this.r = r;
            this.pharmacy = pharmacy;
            this.view = view;
        }
    }

    /** A plain status change (no stock change) in one IMMEDIATE transaction. */
    private Changed changeStatus(int reservationId, List<ReservationStatus> from, ReservationStatus to, String wrongStep)
            throws ServiceException, SQLException {
        Pharmacy pharmacy = myPharmacy();
        Reservation r;
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                r = loadOwn(con, reservationId, pharmacy);
                if (!from.contains(r.getStatus())) {
                    throw new ServiceException(wrongStep + " (" + r.getCode() + " is " + r.getStatus().toString().toLowerCase() + ".)");
                }
                if (!reservationDao.changeStatus(con, r.getId(), from, to)) {
                    throw new ServiceException(changedMeanwhile());
                }
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        return new Changed(r, pharmacy, reservationDao.findView(r.getId()));
    }

    /** Patient notification, audit, cache, trust score. */
    private void after(Changed c, String action, String patientMessage) throws SQLException {
        notificationService.notifyUser(c.r.getPatientId(), NotificationType.RESERVATION, patientMessage,
                NotificationService.LINK_RESERVATION, c.r.getId());
        auditService.log(action, "reservations", c.r.getId(), c.r.getCode() + ": " + c.view.getMedicineShort()
                + " x " + c.r.getQuantity() + " → " + c.view.getReservation().getStatus());
        AvailabilityCache.invalidateMedicine(c.r.getMedicineId());
        trustScoreService.recalculateQuietly(c.pharmacy.getId());
    }

    private Reservation loadOwn(Connection con, int reservationId, Pharmacy pharmacy) throws ServiceException, SQLException {
        Reservation r = reservationDao.findById(con, reservationId);
        if (r == null || r.getPharmacyId() != pharmacy.getId()) {
            throw new ServiceException("This reservation was not found at your pharmacy.");
        }
        return r;
    }

    private static String changedMeanwhile() {
        return "This reservation was changed meanwhile. The list was refreshed.";
    }

    private Pharmacy myPharmacy() throws ServiceException, SQLException {
        if (!Session.hasRole(Role.PHARMACY) || Session.getPharmacy() == null) {
            throw new ServiceException("Only a pharmacy account can manage reservations.");
        }
        Pharmacy p = pharmacyDao.findById(Session.getPharmacy().getId());
        if (p == null) {
            throw new ServiceException("Your pharmacy no longer exists.");
        }
        return p;
    }

    private static StockMovement movement(int inventoryId, int change, MovementReason reason, String note) {
        StockMovement m = new StockMovement();
        m.setInventoryId(inventoryId);
        m.setChangeQty(change);
        m.setReason(reason);
        m.setUserId(Session.getUserId());
        m.setNote(note);
        return m;
    }
}
