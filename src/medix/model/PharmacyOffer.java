package medix.model;

import java.util.List;

/**
 * What the pharmacy detail screen shows: one VERIFIED pharmacy, one medicine and its
 * non-expired batches there (earliest expiry first). Not a table.
 */
public class PharmacyOffer {

    private final Pharmacy pharmacy;
    private final String areaName;
    private final double distanceKm;
    private final Medicine medicine;
    private final List<Inventory> batches;
    private final boolean openNow;

    public PharmacyOffer(Pharmacy pharmacy, String areaName, double distanceKm, Medicine medicine,
            List<Inventory> batches, boolean openNow) {
        this.pharmacy = pharmacy;
        this.areaName = areaName;
        this.distanceKm = distanceKm;
        this.medicine = medicine;
        this.batches = batches;
        this.openNow = openNow;
    }

    public Pharmacy getPharmacy() {
        return pharmacy;
    }

    public String getAreaName() {
        return areaName;
    }

    /** From the centre of the area the patient searched from. */
    public double getDistanceKm() {
        return distanceKm;
    }

    public Medicine getMedicine() {
        return medicine;
    }

    /** Non-expired batches, earliest expiry first (FEFO). */
    public List<Inventory> getBatches() {
        return batches;
    }

    public boolean isOpenNow() {
        return openNow;
    }

    /** Units available over all listed batches. */
    public int getTotalAvailable() {
        int sum = 0;
        for (Inventory i : batches) {
            sum += Math.max(0, i.getAvailableQty());
        }
        return sum;
    }
}
