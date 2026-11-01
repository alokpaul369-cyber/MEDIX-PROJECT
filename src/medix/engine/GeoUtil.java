package medix.engine;

import java.util.List;
import java.util.Locale;
import medix.model.Area;

/**
 * Geography helpers: straight-line distance (haversine) and nearest area.
 * Used by registration (Part 2) and search ranking (Part 5).
 */
public final class GeoUtil {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /** Bangladesh bounding box used to validate typed coordinates. */
    public static final double MIN_LAT = 20.5;
    public static final double MAX_LAT = 26.7;
    public static final double MIN_LON = 88.0;
    public static final double MAX_LON = 92.7;

    private GeoUtil() {
    }

    /** Distance in kilometres between (lat1, lon1) and (lat2, lon2), given in degrees. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static boolean isInsideBangladesh(double lat, double lon) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON;
    }

    /** The area whose centre is closest to the point, or null if the list is empty. */
    public static Area nearestArea(List<Area> areas, double lat, double lon) {
        Area best = null;
        double bestKm = Double.MAX_VALUE;
        for (Area a : areas) {
            double km = distanceKm(lat, lon, a.getLatitude(), a.getLongitude());
            if (km < bestKm) {
                bestKm = km;
                best = a;
            }
        }
        return best;
    }

    /** Coordinate with 4 decimals, e.g. "23.7461". */
    public static String formatCoordinate(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    /** Always one decimal, e.g. "0.8 km", "2.6 km" (search results). */
    public static String formatKm(double km) {
        return String.format(Locale.US, "%.1f km", km);
    }

    /** e.g. "850 m" or "3.4 km". */
    public static String formatDistance(double km) {
        if (km < 1) {
            return Math.round(km * 1000) + " m";
        }
        return String.format(Locale.US, "%.1f km", km);
    }
}
