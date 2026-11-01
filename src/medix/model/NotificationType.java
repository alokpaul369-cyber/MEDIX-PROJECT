package medix.model;

/**
 * Kind of notification: notifications.type.
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 * {@link #toString()} is the label shown in the UI.
 */
public enum NotificationType {
    LOW_STOCK("Low stock"),
    EXPIRY("Expiry"),
    RESERVATION("Reservation"),
    STALE("Stale stock"),
    VERIFICATION("Verification"),
    REPORT("Report"),
    INFO("Info");

    private final String label;

    NotificationType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
