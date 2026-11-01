package medix.model;

/**
 * A possible shortage on the dashboard: a medicine that was searched in an area and found
 * nowhere (search_logs rows with results_count = 0). Not a table.
 */
public class ShortageRow {

    private final int medicineId;
    private final String medicineLabel;
    private final String areaName;
    private final int failed;

    public ShortageRow(int medicineId, String medicineLabel, String areaName, int failed) {
        this.medicineId = medicineId;
        this.medicineLabel = medicineLabel;
        this.areaName = areaName;
        this.failed = failed;
    }

    public int getMedicineId() {
        return medicineId;
    }

    public String getMedicineLabel() {
        return medicineLabel;
    }

    public String getAreaName() {
        return areaName;
    }

    public int getFailed() {
        return failed;
    }
}
