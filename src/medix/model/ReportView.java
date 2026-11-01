package medix.model;

/**
 * A report with the names needed on the admin Reports screen (reporter, pharmacy, reservation).
 * Not a table: ReportDao builds it with a JOIN.
 */
public class ReportView {

    private final Report report;
    private final String reporterName;
    private final String reporterEmail;
    private final String pharmacyName;
    private final PharmacyStatus pharmacyStatus;
    private final int pharmacyTrustScore;
    private final int pharmacyOwnerId;
    private final String reservationCode;
    private final String reservationSummary;

    public ReportView(Report report, String reporterName, String reporterEmail, String pharmacyName,
            PharmacyStatus pharmacyStatus, int pharmacyTrustScore, int pharmacyOwnerId, String reservationCode,
            String reservationSummary) {
        this.report = report;
        this.reporterName = reporterName;
        this.reporterEmail = reporterEmail;
        this.pharmacyName = pharmacyName;
        this.pharmacyStatus = pharmacyStatus;
        this.pharmacyTrustScore = pharmacyTrustScore;
        this.pharmacyOwnerId = pharmacyOwnerId;
        this.reservationCode = reservationCode;
        this.reservationSummary = reservationSummary;
    }

    public Report getReport() {
        return report;
    }

    public String getReporterName() {
        return reporterName;
    }

    public String getReporterEmail() {
        return reporterEmail;
    }

    public String getPharmacyName() {
        return pharmacyName;
    }

    public PharmacyStatus getPharmacyStatus() {
        return pharmacyStatus;
    }

    public int getPharmacyTrustScore() {
        return pharmacyTrustScore;
    }

    public int getPharmacyOwnerId() {
        return pharmacyOwnerId;
    }

    /** null when the report is not about a reservation. */
    public String getReservationCode() {
        return reservationCode;
    }

    /** e.g. "Seclo 20 mg Capsule × 4 · Expired · 01 Nov 2026"; null without a reservation. */
    public String getReservationSummary() {
        return reservationSummary;
    }
}
