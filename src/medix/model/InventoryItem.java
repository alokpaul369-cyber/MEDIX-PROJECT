package medix.model;

import medix.util.DateUtil;

/**
 * One inventory batch together with its medicine, for the inventory screen.
 * Not a table: InventoryDao builds it with a JOIN.
 *
 * Stock states (a batch can be in several):
 * <ul>
 *   <li>expired: expiry date before today</li>
 *   <li>expiring soon: expires today or within {@link #EXPIRING_DAYS} days</li>
 *   <li>low stock: available &lt;= low_stock_threshold</li>
 *   <li>out of stock: available == 0</li>
 * </ul>
 */
public class InventoryItem {

    public static final int EXPIRING_DAYS = 60;

    private final Inventory inventory;
    private final Medicine medicine;

    public InventoryItem(Inventory inventory, Medicine medicine) {
        this.inventory = inventory;
        this.medicine = medicine;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public Medicine getMedicine() {
        return medicine;
    }

    /** e.g. "Napa 500 mg Tablet". */
    public String getMedicineLabel() {
        return medicine.getBrandName() + " " + medicine.getStrength() + " " + medicine.getForm();
    }

    public int getAvailable() {
        return inventory.getAvailableQty();
    }

    /** Days until expiry; negative when expired. */
    public long getDaysToExpiry() {
        return DateUtil.daysUntil(inventory.getExpiryDate());
    }

    public boolean isExpired() {
        return getDaysToExpiry() < 0;
    }

    public boolean isExpiringSoon() {
        long days = getDaysToExpiry();
        return days >= 0 && days <= EXPIRING_DAYS;
    }

    public boolean isLowStock() {
        return getAvailable() <= inventory.getLowStockThreshold();
    }

    public boolean isOutOfStock() {
        return getAvailable() <= 0;
    }

    /** quantity × price. */
    public double getStockValue() {
        return inventory.getQuantity() * inventory.getPrice();
    }

    @Override
    public String toString() {
        return getMedicineLabel() + " (batch " + inventory.getBatchNo() + ")";
    }
}
