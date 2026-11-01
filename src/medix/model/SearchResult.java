package medix.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the search screen shows for one search. Not a table.
 */
public class SearchResult {

    /** Another brand with the same generic name that is in stock nearby. */
    public static class Alternative {
        private final Medicine medicine;
        private final int pharmacies;
        private final double nearestKm;

        public Alternative(Medicine medicine, int pharmacies, double nearestKm) {
            this.medicine = medicine;
            this.pharmacies = pharmacies;
            this.nearestKm = nearestKm;
        }

        public Medicine getMedicine() {
            return medicine;
        }

        public int getPharmacies() {
            return pharmacies;
        }

        public double getNearestKm() {
            return nearestKm;
        }
    }

    private String query;
    /** The medicine the results are for; null when nothing in the catalog matched. */
    private Medicine medicine;
    private String didYouMean;
    private List<Medicine> otherMatches = new ArrayList<Medicine>();
    private List<PharmacyStock> rows = new ArrayList<PharmacyStock>();
    private List<Alternative> alternatives = new ArrayList<Alternative>();
    private Area area;
    /** null = any distance */
    private Double radiusKm;
    private boolean cached;
    private long elapsedMillis;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Medicine getMedicine() {
        return medicine;
    }

    public void setMedicine(Medicine medicine) {
        this.medicine = medicine;
    }

    /** e.g. "Seclo" when the user typed "seclow"; null otherwise. */
    public String getDidYouMean() {
        return didYouMean;
    }

    public void setDidYouMean(String didYouMean) {
        this.didYouMean = didYouMean;
    }

    /** Other medicines that matched the text equally well (e.g. other Paracetamol brands). */
    public List<Medicine> getOtherMatches() {
        return otherMatches;
    }

    public void setOtherMatches(List<Medicine> otherMatches) {
        this.otherMatches = otherMatches;
    }

    public List<PharmacyStock> getRows() {
        return rows;
    }

    public void setRows(List<PharmacyStock> rows) {
        this.rows = rows;
    }

    /** Filled only when there are no rows: same generic, other brands, in stock within the radius. */
    public List<Alternative> getAlternatives() {
        return alternatives;
    }

    public void setAlternatives(List<Alternative> alternatives) {
        this.alternatives = alternatives;
    }

    public Area getArea() {
        return area;
    }

    public void setArea(Area area) {
        this.area = area;
    }

    public Double getRadiusKm() {
        return radiusKm;
    }

    public void setRadiusKm(Double radiusKm) {
        this.radiusKm = radiusKm;
    }

    /** The pharmacy rows came from AvailabilityCache. */
    public boolean isCached() {
        return cached;
    }

    public void setCached(boolean cached) {
        this.cached = cached;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public void setElapsedMillis(long elapsedMillis) {
        this.elapsedMillis = elapsedMillis;
    }
}
