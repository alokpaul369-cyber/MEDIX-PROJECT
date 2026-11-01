package medix.service;

import java.sql.SQLException;
import medix.dao.InventoryDao;
import medix.dao.NotificationDao;
import medix.dao.PharmacyDao;
import medix.engine.SearchEngine;
import medix.model.BatchAlert;
import medix.model.InventoryItem;
import medix.model.NotificationType;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.util.DateUtil;

/**
 * Stock alerts for pharmacy owners. Every alert is sent at most once per batch (or pharmacy)
 * per day: the notification links to the record and a same-day duplicate is skipped.
 * Used by InventoryService (right after a change) and by the background jobs.
 */
public class AlertService {

    /** Batches expiring within this many days get an EXPIRY alert. */
    public static final int EXPIRY_WARNING_DAYS = InventoryItem.EXPIRING_DAYS;

    private final InventoryDao inventoryDao = new InventoryDao();
    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final NotificationDao notificationDao = new NotificationDao();
    private final NotificationService notificationService = new NotificationService();

    /** "Seclo 20 mg (batch SC-2510)". */
    public static String batchName(InventoryItem item) {
        return item.getMedicine().getBrandName() + " " + item.getMedicine().getStrength()
                + " (batch " + item.getInventory().getBatchNo() + ")";
    }

    /** "Seclo 20 mg (batch SC-2510) has 2 left. Alert level is 10." */
    public static String lowStockMessage(InventoryItem item) {
        return batchName(item) + " has " + item.getAvailable() + " left. Alert level is "
                + item.getInventory().getLowStockThreshold() + ".";
    }

    /** "… expires in 45 days: 30 units." / "… expires today: …" / "… has expired. Remove 40 units from the shelf." */
    public static String expiryMessage(InventoryItem item) {
        long days = item.getDaysToExpiry();
        String units = units(item.getInventory().getQuantity());
        if (days < 0) {
            return batchName(item) + " has expired. Remove " + units + " from the shelf.";
        }
        String when = days == 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days";
        return batchName(item) + " expires " + when + ": " + units + ".";
    }

    /** "1 unit", "30 units". */
    private static String units(int n) {
        return n + (n == 1 ? " unit" : " units");
    }

    /** LOW_STOCK to the owner unless already sent today for this batch. Returns true when sent. */
    public boolean notifyLowStockOnce(int ownerId, InventoryItem item) throws SQLException {
        if (!item.isLowStock()) {
            return false;
        }
        return sendOncePerDay(ownerId, NotificationType.LOW_STOCK, lowStockMessage(item),
                NotificationService.LINK_INVENTORY, item.getInventory().getId());
    }

    /** LowStockJob: every non-expired low batch of every VERIFIED pharmacy. Returns alerts sent. */
    public int runLowStockCheck() throws SQLException {
        int sent = 0;
        for (BatchAlert a : inventoryDao.findLowStockAlerts()) {
            if (notifyLowStockOnce(a.getOwnerId(), a.getItem())) {
                sent++;
            }
        }
        return sent;
    }

    /** ExpirySweepJob: batches with units that expire within 60 days or have expired. Returns alerts sent. */
    public int runExpirySweep() throws SQLException {
        int sent = 0;
        for (BatchAlert a : inventoryDao.findExpiryAlerts(EXPIRY_WARNING_DAYS)) {
            if (sendOncePerDay(a.getOwnerId(), NotificationType.EXPIRY, expiryMessage(a.getItem()),
                    NotificationService.LINK_INVENTORY, a.getItem().getInventory().getId())) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * StaleStockJob: VERIFIED pharmacies whose newest stock update is 24+ hours old get one STALE
     * notification per day. Returns how many were notified.
     */
    public int runStaleCheck() throws SQLException {
        int sent = 0;
        for (Pharmacy p : pharmacyDao.findByStatus(PharmacyStatus.VERIFIED)) {
            String last = inventoryDao.lastStockUpdate(p.getId());
            if (last == null || !DateUtil.isOlderThanHours(last, SearchEngine.STALE_HOURS)) {
                continue;
            }
            String message = "Your stock was last updated " + DateUtil.ago(last) + " ago. Patients see a warning. "
                    + "Update your stock or press \"Confirm all stock is correct\".";
            if (sendOncePerDay(p.getOwnerId(), NotificationType.STALE, message,
                    NotificationService.LINK_PHARMACY, p.getId())) {
                sent++;
            }
        }
        return sent;
    }

    private boolean sendOncePerDay(int userId, NotificationType type, String message, String linkEntity, int linkId)
            throws SQLException {
        if (notificationDao.existsForLinkSince(userId, type, linkEntity, linkId, DateUtil.today() + " 00:00:00")) {
            return false;
        }
        notificationService.notifyUser(userId, type, message, linkEntity, linkId);
        return true;
    }
}
