package medix.model;

/**
 * A batch that needs an alert (low stock, expiring, expired) with the pharmacy owner to notify.
 * Not a table: built by InventoryDao for the background jobs.
 */
public class BatchAlert {

    private final InventoryItem item;
    private final int ownerId;
    private final String pharmacyName;

    public BatchAlert(InventoryItem item, int ownerId, String pharmacyName) {
        this.item = item;
        this.ownerId = ownerId;
        this.pharmacyName = pharmacyName;
    }

    public InventoryItem getItem() {
        return item;
    }

    public int getOwnerId() {
        return ownerId;
    }

    public String getPharmacyName() {
        return pharmacyName;
    }
}
