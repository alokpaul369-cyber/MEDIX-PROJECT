package medix.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import medix.model.PharmacyStock;

/**
 * In-memory cache of search results (stands in for Redis in the report).
 *
 * Key: medicineId + areaId + radius. Entries expire after 60 seconds; at most 200 entries are
 * kept and the least recently used one is dropped first (LinkedHashMap in access order).
 * All methods are synchronized. Callers must invalidate a medicine whenever its stock or a
 * reservation for it changes ({@link #invalidateMedicine}), and clear everything when a
 * pharmacy's visibility changes (verification, profile edits).
 */
public final class AvailabilityCache {

    public static final long TTL_MILLIS = 60 * 1000L;
    public static final int MAX_ENTRIES = 200;

    private static final class Entry {
        final List<PharmacyStock> rows;
        final long storedAt;

        Entry(List<PharmacyStock> rows, long storedAt) {
            this.rows = rows;
            this.storedAt = storedAt;
        }
    }

    private static final LinkedHashMap<String, Entry> CACHE = new LinkedHashMap<String, Entry>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private AvailabilityCache() {
    }

    /** radiusKm null = any distance. */
    public static String key(int medicineId, int areaId, Double radiusKm) {
        return medicineId + ":" + areaId + ":" + (radiusKm == null ? "any" : String.valueOf(radiusKm));
    }

    /** A copy of the cached rows, or null when missing or older than 60 seconds. */
    public static synchronized List<PharmacyStock> get(String key) {
        Entry e = CACHE.get(key);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.storedAt > TTL_MILLIS) {
            CACHE.remove(key);
            return null;
        }
        return new ArrayList<PharmacyStock>(e.rows);
    }

    public static synchronized void put(String key, List<PharmacyStock> rows) {
        CACHE.put(key, new Entry(new ArrayList<PharmacyStock>(rows), System.currentTimeMillis()));
    }

    /** Drops every entry for the medicine (all areas and radii). */
    public static synchronized void invalidateMedicine(int medicineId) {
        String prefix = medicineId + ":";
        Iterator<String> it = CACHE.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) {
                it.remove();
            }
        }
    }

    public static synchronized void clear() {
        CACHE.clear();
    }

    public static synchronized int size() {
        return CACHE.size();
    }
}
