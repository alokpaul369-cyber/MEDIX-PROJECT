package medix.model;

/**
 * Life cycle of a reservation: reservations.status.
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 * {@link #toString()} is the label shown in the UI.
 * PENDING → CONFIRMED → READY → COMPLETED, or CANCELLED / EXPIRED.
 */
public enum ReservationStatus {
    PENDING("Pending"),
    CONFIRMED("Confirmed"),
    READY("Ready"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    EXPIRED("Expired");

    private final String label;

    ReservationStatus(String label) {
        this.label = label;
    }

    /** PENDING, CONFIRMED and READY still hold units (reserved_qty) at the pharmacy. */
    public boolean isActive() {
        return this == PENDING || this == CONFIRMED || this == READY;
    }

    @Override
    public String toString() {
        return label;
    }
}
