package medix.model;

/**
 * Row of the pharmacies table. Owned by exactly one PHARMACY user.
 */
public class Pharmacy {

    private int id;
    private int ownerId;
    private String name;
    private String licenseNo;
    private String licenseDocPath;
    private String address;
    private Integer areaId;
    private Double latitude;
    private Double longitude;
    private String phone;
    private String openTime;
    private String closeTime;
    private PharmacyStatus status;
    /** Same as the column DEFAULT; an int field would otherwise start at 0 and override it. */
    public static final int DEFAULT_TRUST_SCORE = 80;

    private int trustScore = DEFAULT_TRUST_SCORE;
    /** Trust points taken by the last nightly stale-stock check (0 or 5). */
    private int stalePenalty;

    public int getStalePenalty() {
        return stalePenalty;
    }

    public void setStalePenalty(int stalePenalty) {
        this.stalePenalty = stalePenalty;
    }
    private Integer verifiedBy;
    private String verifiedAt;
    private String rejectReason;
    private String createdAt;

    public Pharmacy() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(int ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLicenseNo() {
        return licenseNo;
    }

    public void setLicenseNo(String licenseNo) {
        this.licenseNo = licenseNo;
    }

    public String getLicenseDocPath() {
        return licenseDocPath;
    }

    public void setLicenseDocPath(String licenseDocPath) {
        this.licenseDocPath = licenseDocPath;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Integer getAreaId() {
        return areaId;
    }

    public void setAreaId(Integer areaId) {
        this.areaId = areaId;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
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

    public PharmacyStatus getStatus() {
        return status;
    }

    public void setStatus(PharmacyStatus status) {
        this.status = status;
    }

    public int getTrustScore() {
        return trustScore;
    }

    public void setTrustScore(int trustScore) {
        this.trustScore = trustScore;
    }

    public Integer getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(Integer verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public String getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(String verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return name;
    }
}
