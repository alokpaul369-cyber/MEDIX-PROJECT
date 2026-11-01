package medix.model;

/**
 * User role: users.role.
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 * {@link #toString()} is the label shown in the UI.
 */
public enum Role {
    PATIENT("Patient"),
    PHARMACY("Pharmacy"),
    ADMIN("Admin");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
