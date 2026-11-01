package medix.model;

/**
 * Verification state of a pharmacy: pharmacies.status.
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 * {@link #toString()} is the label shown in the UI.
 */
public enum PharmacyStatus {
    PENDING("Pending"),
    VERIFIED("Verified"),
    REJECTED("Rejected"),
    SUSPENDED("Suspended");

    private final String label;

    PharmacyStatus(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
