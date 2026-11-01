package medix.model;

/**
 * A pharmacy together with its owner and area name, for admin screens.
 * Not a table: built by VerificationService from pharmacies, users and areas.
 */
public class PharmacyDetails {

    private final Pharmacy pharmacy;
    private final User owner;
    private final String areaName;
    private final boolean reverificationDue;

    public PharmacyDetails(Pharmacy pharmacy, User owner, String areaName, boolean reverificationDue) {
        this.pharmacy = pharmacy;
        this.owner = owner;
        this.areaName = areaName;
        this.reverificationDue = reverificationDue;
    }

    public Pharmacy getPharmacy() {
        return pharmacy;
    }

    /** The PHARMACY user who owns it; null only if the account is missing. */
    public User getOwner() {
        return owner;
    }

    /** Area name, or "" when the pharmacy has no area. */
    public String getAreaName() {
        return areaName;
    }

    /** VERIFIED more than 12 months ago: the license must be checked again. */
    public boolean isReverificationDue() {
        return reverificationDue;
    }

    @Override
    public String toString() {
        return pharmacy.getName();
    }
}
