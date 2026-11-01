package medix.model;

/**
 * Why a pharmacy cancelled a reservation. Stored in reservations.cancel_reason as name()
 * (optionally followed by ": details"); OUT_OF_STOCK lowers the trust score.
 * {@link #toString()} is the label shown in the UI.
 */
public enum CancelReason {
    OUT_OF_STOCK("Out of stock"),
    PATIENT_ASKED("Patient asked"),
    OTHER("Other");

    private final String label;

    CancelReason(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
