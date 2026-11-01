package medix.model;

/**
 * Why a patient reported a pharmacy: reports.reason.
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 * {@link #toString()} is the label shown in the UI.
 */
public enum ReportReason {
    WRONG_STOCK("Wrong stock"),
    RESERVATION_NOT_HONOURED("Reservation not honoured"),
    WRONG_PRICE("Wrong price"),
    FAKE_PHARMACY("Fake pharmacy"),
    OTHER("Other");

    private final String label;

    ReportReason(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
