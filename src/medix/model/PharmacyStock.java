package medix.model;

/**
 * One search result row: a VERIFIED pharmacy that has a medicine in stock.
 * Not a table: built by InventoryDao.findStockedPharmacies (one row per pharmacy,
 * summed over its non-expired batches). distanceKm is filled in by SearchEngine.
 */
public class PharmacyStock {

    private int pharmacyId;
    private String pharmacyName;
    private String areaName;
    /** Pharmacy coordinates; when missing, the centre of its area is used. */
    private double latitude;
    private double longitude;
    private String phone;
    private String address;
    private String openTime;
    private String closeTime;
    private int trustScore;
    /** SUM(quantity − reserved_qty) over non-expired batches. */
    private int available;
    /** Lowest price among those batches (Tk). */
    private double lowestPrice;
    /** Newest updated_at among those batches. */
    private String lastUpdated;
    private double distanceKm;

    public int getPharmacyId() {
        return pharmacyId;
    }

    public void setPharmacyId(int pharmacyId) {
        this.pharmacyId = pharmacyId;
    }

    public String getPharmacyName() {
        return pharmacyName;
    }

    public void setPharmacyName(String pharmacyName) {
        this.pharmacyName = pharmacyName;
    }

    public String getAreaName() {
        return areaName;
    }

    public void setAreaName(String areaName) {
        this.areaName = areaName;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getOpenTime() {
        return openTime;
    }

    public void setOpenTime(String openTime) {
        this.openTime = openTime;
    }

    public String getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(String closeTime) {
        this.closeTime = closeTime;
    }

    public int getTrustScore() {
        return trustScore;
    }

    public void setTrustScore(int trustScore) {
        this.trustScore = trustScore;
    }

    public int getAvailable() {
        return available;
    }

    public void setAvailable(int available) {
        this.available = available;
    }

    public double getLowestPrice() {
        return lowestPrice;
    }

    public void setLowestPrice(double lowestPrice) {
        this.lowestPrice = lowestPrice;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(double distanceKm) {
        this.distanceKm = distanceKm;
    }

    @Override
    public String toString() {
        return pharmacyName;
    }
}
