package medix.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import medix.dao.InventoryDao;
import medix.dao.MedicineDao;
import medix.dao.SearchLogDao;
import medix.engine.AvailabilityCache;
import medix.engine.SearchEngine;
import medix.model.Area;
import medix.model.Medicine;
import medix.model.PharmacyStock;
import medix.model.SearchLog;
import medix.model.SearchResult;
import medix.util.ErrorLog;
import medix.util.Session;

/**
 * Patient medicine search: name matching (SearchEngine), nearby pharmacies with stock
 * (InventoryDao + AvailabilityCache), "other brands" suggestions and search_logs.
 * Privacy: search_logs keeps only the chosen AREA, never an exact location.
 */
public class SearchService {

    public static final int MIN_SUGGEST_LETTERS = 2;
    public static final int MAX_SUGGESTIONS = 8;
    /** How many equally good matches are tried when picking the medicine to show. */
    private static final int MAX_CANDIDATES = 8;

    private final MedicineDao medicineDao = new MedicineDao();
    private final InventoryDao inventoryDao = new InventoryDao();
    private final SearchLogDao searchLogDao = new SearchLogDao();

    /** Autocomplete: up to 8 medicines for 2+ letters, spelling-tolerant. */
    public List<Medicine> suggest(String text) throws SQLException {
        if (SearchEngine.normalize(text).length() < MIN_SUGGEST_LETTERS) {
            return new ArrayList<Medicine>();
        }
        List<Medicine> all = SearchEngine.match(text, medicineDao.findAll()).getMedicines();
        return new ArrayList<Medicine>(all.subList(0, Math.min(MAX_SUGGESTIONS, all.size())));
    }

    /**
     * Runs a search and writes a search_logs row.
     *
     * @param query    the text in the search box
     * @param chosen   the medicine picked in the autocomplete, or null to match the text
     * @param from     the area whose centre distances are measured from
     * @param radiusKm null = any distance
     */
    public SearchResult search(String query, Medicine chosen, Area from, Double radiusKm, SearchEngine.Sort sort)
            throws SQLException {
        long start = System.currentTimeMillis();
        SearchResult result = new SearchResult();
        result.setQuery(query);
        result.setArea(from);
        result.setRadiusKm(radiusKm);

        Medicine medicine = chosen;
        List<PharmacyStock> rows = null;
        boolean[] cached = new boolean[1];
        if (medicine == null) {
            SearchEngine.MatchResult match = SearchEngine.match(query, medicineDao.findAll());
            if (!match.isEmpty()) {
                result.setDidYouMean(match.getDidYouMean());
                // among the equally good matches, show the one most pharmacies nearby have
                List<Medicine> best = match.getBestMedicines();
                int mostRows = -1;
                for (Medicine m : best.subList(0, Math.min(MAX_CANDIDATES, best.size()))) {
                    boolean[] fromCache = new boolean[1];
                    List<PharmacyStock> r = available(m, from, radiusKm, fromCache);
                    if (r.size() > mostRows) {
                        mostRows = r.size();
                        medicine = m;
                        rows = r;
                        cached[0] = fromCache[0];
                    }
                }
                List<Medicine> others = new ArrayList<Medicine>(best);
                others.remove(medicine);
                result.setOtherMatches(others);
            }
        }
        if (medicine != null) {
            result.setMedicine(medicine);
            if (rows == null) {
                rows = available(medicine, from, radiusKm, cached);
            }
            result.setCached(cached[0]);
            List<PharmacyStock> sorted = new ArrayList<PharmacyStock>(rows);
            SearchEngine.sort(sorted, sort);
            result.setRows(sorted);
            if (sorted.isEmpty()) {
                result.setAlternatives(alternatives(medicine, from, radiusKm));
            }
        }
        result.setElapsedMillis(System.currentTimeMillis() - start);
        log(query, medicine, from, result.getRows().size());
        return result;
    }

    /**
     * Pharmacies within the radius that have the medicine, from the cache when fresh.
     * cachedOut[0] tells whether the rows came from the cache.
     */
    private List<PharmacyStock> available(Medicine m, Area from, Double radiusKm, boolean[] cachedOut)
            throws SQLException {
        String key = AvailabilityCache.key(m.getId(), from.getId(), radiusKm);
        List<PharmacyStock> rows = AvailabilityCache.get(key);
        cachedOut[0] = rows != null;
        if (rows != null) {
            return rows;
        }
        rows = SearchEngine.withinRadius(inventoryDao.findStockedPharmacies(m.getId()), from, radiusKm);
        AvailabilityCache.put(key, rows);
        return rows;
    }

    /** Same generic name, other brands/strengths, that are in stock within the radius. */
    private List<SearchResult.Alternative> alternatives(Medicine m, Area from, Double radiusKm) throws SQLException {
        List<SearchResult.Alternative> list = new ArrayList<SearchResult.Alternative>();
        for (Medicine other : medicineDao.findByGeneric(m.getGenericName(), m.getId())) {
            List<PharmacyStock> rows = available(other, from, radiusKm, new boolean[1]);
            if (!rows.isEmpty()) {
                list.add(new SearchResult.Alternative(other, rows.size(), rows.get(0).getDistanceKm()));
            }
        }
        return list;
    }

    /** Days a search_logs row is kept (privacy: minimise search history). */
    public static final int SEARCH_LOG_DAYS = 90;

    /** SearchLogCleanupJob: deletes search_logs older than 90 days. Returns how many were deleted. */
    public int deleteOldSearchLogs() throws SQLException {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_MONTH, -SEARCH_LOG_DAYS);
        return searchLogDao.deleteOlderThan(medix.util.DateUtil.formatDateTime(c.getTime()));
    }

    /** How many searches of the logged-in user are stored (Profile, privacy). */
    public int countMySearches() throws SQLException {
        Integer me = Session.getUserId();
        return me == null ? 0 : searchLogDao.countByUser(me);
    }

    /** Privacy: deletes every search_logs row of the logged-in user. Returns how many. */
    public int deleteMySearchHistory() throws ServiceException, SQLException {
        Integer me = Session.getUserId();
        if (me == null) {
            throw new ServiceException("Please log in first.");
        }
        int deleted = searchLogDao.deleteByUser(me);
        AuditService audit = new AuditService();
        audit.log("SEARCH_HISTORY_DELETED", "users", me, deleted + " search(es) deleted");
        return deleted;
    }

    private void log(String query, Medicine medicine, Area from, int results) {
        SearchLog log = new SearchLog();
        log.setUserId(Session.getUserId());
        log.setQuery(query == null ? "" : query.trim());
        log.setMedicineId(medicine == null ? null : medicine.getId());
        log.setAreaId(from == null ? null : from.getId());
        log.setResultsCount(results);
        try {
            searchLogDao.insert(log);
        } catch (SQLException e) {
            ErrorLog.log("Search log failed", e);
        }
    }
}
