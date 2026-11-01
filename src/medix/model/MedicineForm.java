package medix.model;

/**
 * Dosage form of a medicine: medicines.form.
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 * {@link #toString()} is the label shown in combo boxes and tables.
 */
public enum MedicineForm {
    TABLET("Tablet"),
    CAPSULE("Capsule"),
    SYRUP("Syrup"),
    SUSPENSION("Suspension"),
    INJECTION("Injection"),
    DROPS("Drops"),
    CREAM("Cream"),
    INHALER("Inhaler"),
    POWDER("Powder / sachet");

    private final String label;

    MedicineForm(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
