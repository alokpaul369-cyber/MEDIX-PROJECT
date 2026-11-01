package medix.service;

import java.sql.SQLException;
import java.util.List;
import medix.dao.AreaDao;
import medix.engine.GeoUtil;
import medix.model.Area;

/**
 * Areas for combo boxes and offline "find from address".
 */
public class AreaService {

    private final AreaDao areaDao = new AreaDao();

    /** All areas sorted by name. */
    public List<Area> getAreas() throws SQLException {
        return areaDao.findAllByName();
    }

    public Area findById(int id) throws SQLException {
        return areaDao.findById(id);
    }

    /**
     * Finds a known area name inside free address text, ignoring case and punctuation,
     * so "House 12, Road 5, Mirpur-10" matches "Mirpur 10". The longest matching name wins.
     * Returns null when no area is mentioned.
     */
    public Area findAreaInText(String text) throws SQLException {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String haystack = " " + normalize(text) + " ";
        Area best = null;
        int bestLength = 0;
        for (Area a : areaDao.findAll()) {
            String needle = normalize(a.getName());
            if (!needle.isEmpty() && haystack.contains(" " + needle + " ") && needle.length() > bestLength) {
                best = a;
                bestLength = needle.length();
            }
        }
        return best;
    }

    /** The area closest to the coordinates, or null if there are no areas. */
    public Area findNearestArea(double lat, double lon) throws SQLException {
        return GeoUtil.nearestArea(areaDao.findAll(), lat, lon);
    }

    /** Lower case; every run of characters other than letters/digits becomes one space. */
    static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }
}
