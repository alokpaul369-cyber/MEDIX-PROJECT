package medix.model;

/**
 * Admin handling state of a report: reports.status.
 * RESOLVED means the admin upheld the report (it lowers the pharmacy's trust score).
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 * {@link #toString()} is the label shown in the UI.
 */
public enum ReportStatus {
    OPEN("Open"),
    RESOLVED("Upheld"),
    DISMISSED("Dismissed");

    private final String label;

    ReportStatus(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
