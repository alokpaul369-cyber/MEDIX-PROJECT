package medix.model;

/**
 * The numbers on the admin dashboard cards for one period (last 7 or 30 days). Not a table.
 */
public class DashboardStats {

    private int days;
    private int verifiedPharmacies;
    private int areasCovered;
    private int pendingPharmacies;
    private int searches;
    private int previousSearches;
    private int completedReservations;
    private int closedReservations;

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        this.days = days;
    }

    public int getVerifiedPharmacies() {
        return verifiedPharmacies;
    }

    public void setVerifiedPharmacies(int verifiedPharmacies) {
        this.verifiedPharmacies = verifiedPharmacies;
    }

    /** Areas that have at least one verified pharmacy. */
    public int getAreasCovered() {
        return areasCovered;
    }

    public void setAreasCovered(int areasCovered) {
        this.areasCovered = areasCovered;
    }

    public int getPendingPharmacies() {
        return pendingPharmacies;
    }

    public void setPendingPharmacies(int pendingPharmacies) {
        this.pendingPharmacies = pendingPharmacies;
    }

    public int getSearches() {
        return searches;
    }

    public void setSearches(int searches) {
        this.searches = searches;
    }

    /** Searches in the period of the same length just before this one. */
    public int getPreviousSearches() {
        return previousSearches;
    }

    public void setPreviousSearches(int previousSearches) {
        this.previousSearches = previousSearches;
    }

    public int getCompletedReservations() {
        return completedReservations;
    }

    public void setCompletedReservations(int completedReservations) {
        this.completedReservations = completedReservations;
    }

    /** Reservations made in the period that are finished (completed, cancelled or expired). */
    public int getClosedReservations() {
        return closedReservations;
    }

    public void setClosedReservations(int closedReservations) {
        this.closedReservations = closedReservations;
    }

    /** Change of searches against the previous period in percent; null when there were none before. */
    public Integer getSearchChangePercent() {
        if (previousSearches == 0) {
            return null;
        }
        return (int) Math.round((searches - previousSearches) * 100.0 / previousSearches);
    }

    /** Completed share of the closed reservations in percent (0 when none are closed). */
    public int getCompletedPercent() {
        return closedReservations == 0 ? 0 : (int) Math.round(completedReservations * 100.0 / closedReservations);
    }
}
