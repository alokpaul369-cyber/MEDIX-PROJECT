package medix.model;

/**
 * A reservation with the names needed on screen (medicine, pharmacy) and the unit price of its
 * batch. Not a table: ReservationDao builds it with a JOIN.
 */
public class ReservationView {

    private final Reservation reservation;
    private final String medicineLabel;
    private final String medicineShort;
    private final String pharmacyName;
    private final String pharmacyPhone;
    private final int pharmacyOwnerId;
    private final Double unitPrice;
    private final String patientName;
    private final String patientPhone;

    public ReservationView(Reservation reservation, String medicineLabel, String medicineShort, String pharmacyName,
            String pharmacyPhone, int pharmacyOwnerId, Double unitPrice, String patientName, String patientPhone) {
        this.reservation = reservation;
        this.medicineLabel = medicineLabel;
        this.medicineShort = medicineShort;
        this.pharmacyName = pharmacyName;
        this.pharmacyPhone = pharmacyPhone;
        this.pharmacyOwnerId = pharmacyOwnerId;
        this.unitPrice = unitPrice;
        this.patientName = patientName;
        this.patientPhone = patientPhone;
    }

    public String getPatientName() {
        return patientName;
    }

    /** The patient's full phone number; show it masked until the reservation is confirmed. */
    public String getPatientPhone() {
        return patientPhone;
    }

    public Reservation getReservation() {
        return reservation;
    }

    /** e.g. "Seclo 20 mg Capsule". */
    public String getMedicineLabel() {
        return medicineLabel;
    }

    /** e.g. "Seclo 20 mg" (used in messages). */
    public String getMedicineShort() {
        return medicineShort;
    }

    public String getPharmacyName() {
        return pharmacyName;
    }

    public String getPharmacyPhone() {
        return pharmacyPhone;
    }

    public int getPharmacyOwnerId() {
        return pharmacyOwnerId;
    }

    /** Price per unit of the reserved batch; null if the batch was deleted. */
    public Double getUnitPrice() {
        return unitPrice;
    }

    /** quantity × unit price, or null when the price is unknown. */
    public Double getAmount() {
        return unitPrice == null ? null : unitPrice * reservation.getQuantity();
    }

    @Override
    public String toString() {
        return reservation.getCode();
    }
}
