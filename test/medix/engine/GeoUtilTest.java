package medix.engine;

import java.util.Arrays;
import medix.model.Area;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeoUtilTest {

    private static final double DHANMONDI_LAT = 23.7461;
    private static final double DHANMONDI_LON = 90.3742;
    private static final double GULSHAN1_LAT = 23.7806;
    private static final double GULSHAN1_LON = 90.4160;

    @Test
    public void dhanmondiToGulshan1IsAbout5Km() {
        double km = GeoUtil.distanceKm(DHANMONDI_LAT, DHANMONDI_LON, GULSHAN1_LAT, GULSHAN1_LON);
        // straight line between the two area centres: about 5.7 km
        assertTrue("was " + km, km > 5.0 && km < 6.5);
    }

    @Test
    public void distanceIsSymmetricAndZeroForTheSamePoint() {
        double there = GeoUtil.distanceKm(DHANMONDI_LAT, DHANMONDI_LON, GULSHAN1_LAT, GULSHAN1_LON);
        double back = GeoUtil.distanceKm(GULSHAN1_LAT, GULSHAN1_LON, DHANMONDI_LAT, DHANMONDI_LON);
        assertEquals(there, back, 1e-9);
        assertEquals(0.0, GeoUtil.distanceKm(DHANMONDI_LAT, DHANMONDI_LON, DHANMONDI_LAT, DHANMONDI_LON), 1e-9);
    }

    @Test
    public void formatsOneDecimal() {
        assertEquals("2.6 km", GeoUtil.formatKm(2.6449));
        assertEquals("0.8 km", GeoUtil.formatKm(0.75));
    }

    @Test
    public void bangladeshBoundingBox() {
        assertTrue(GeoUtil.isInsideBangladesh(DHANMONDI_LAT, DHANMONDI_LON));
        assertFalse(GeoUtil.isInsideBangladesh(28.6, 77.2)); // Delhi
    }

    @Test
    public void nearestArea() {
        Area dhanmondi = area(1, "Dhanmondi", DHANMONDI_LAT, DHANMONDI_LON);
        Area gulshan = area(5, "Gulshan 1", GULSHAN1_LAT, GULSHAN1_LON);
        assertEquals("Gulshan 1", GeoUtil.nearestArea(Arrays.asList(dhanmondi, gulshan), 23.7790, 90.4100).getName());
    }

    private static Area area(int id, String name, double lat, double lon) {
        Area a = new Area();
        a.setId(id);
        a.setName(name);
        a.setLatitude(lat);
        a.setLongitude(lon);
        return a;
    }
}
