package medix.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import medix.model.NamedCount;
import medix.model.SearchLog;
import medix.model.ShortageRow;
import medix.util.DBConnection;
import medix.util.DateUtil;

/**
 * Data access for the search_logs table.
 */
public class SearchLogDao extends BaseDao<SearchLog> {

    @Override
    protected String tableName() {
        return "search_logs";
    }

    @Override
    protected SearchLog mapRow(ResultSet rs) throws SQLException {
        SearchLog s = new SearchLog();
        s.setId(rs.getInt("id"));
        s.setUserId(getInteger(rs, "user_id"));
        s.setQuery(rs.getString("query"));
        s.setMedicineId(getInteger(rs, "medicine_id"));
        s.setAreaId(getInteger(rs, "area_id"));
        s.setResultsCount(rs.getInt("results_count"));
        s.setCreatedAt(rs.getString("created_at"));
        return s;
    }

    public int insert(SearchLog s) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return insert(con, s);
        }
    }

    /** Insert on the caller's connection (many rows in one transaction, e.g. demo data). */
    public int insert(Connection con, SearchLog s) throws SQLException {
        if (s.getCreatedAt() == null) {
            s.setCreatedAt(DateUtil.now());
        }
        int id = executeInsert(con,
                "INSERT INTO search_logs (user_id, query, medicine_id, area_id, results_count, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                s.getUserId(), s.getQuery(), s.getMedicineId(), s.getAreaId(), s.getResultsCount(),
                s.getCreatedAt());
        s.setId(id);
        return id;
    }

    /** Deletes searches logged before the date-time (privacy clean-up); returns how many. */
    public int deleteOlderThan(String dateTime) throws SQLException {
        return executeUpdate("DELETE FROM search_logs WHERE created_at < ?", dateTime);
    }

    public boolean update(SearchLog s) throws SQLException {
        return executeUpdate(
                "UPDATE search_logs SET user_id = ?, query = ?, medicine_id = ?, area_id = ?, "
                + "results_count = ? WHERE id = ?",
                s.getUserId(), s.getQuery(), s.getMedicineId(), s.getAreaId(), s.getResultsCount(),
                s.getId()) > 0;
    }

    // ---------------------------------------------------------------- patient privacy

    public int countByUser(int userId) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM search_logs WHERE user_id = ?", userId);
    }

    /** Deletes every search of the user ("Delete my search history"); returns how many. */
    public int deleteByUser(int userId) throws SQLException {
        return executeUpdate("DELETE FROM search_logs WHERE user_id = ?", userId);
    }

    // ---------------------------------------------------------------- admin dashboard (period [from, to))

    public int countBetween(String from, String to) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM search_logs WHERE created_at >= ? AND created_at < ?", from, to);
    }

    /** Most searched medicines (searches that matched a medicine), most first. Label "Seclo 20 mg". */
    public List<NamedCount> topMedicines(String from, String to, int limit) throws SQLException {
        return query("SELECT s.medicine_id, m.brand_name, m.strength, COUNT(*) AS n FROM search_logs s "
                + "JOIN medicines m ON m.id = s.medicine_id "
                + "WHERE s.created_at >= ? AND s.created_at < ? "
                + "GROUP BY s.medicine_id ORDER BY n DESC, m.brand_name LIMIT ?",
                rs -> new NamedCount(rs.getInt("medicine_id"), rs.getString("brand_name") + " " + rs.getString("strength"),
                        rs.getInt("n")),
                from, to, limit);
    }

    /** Searches per day (key yyyy-MM-dd); days without searches are missing. */
    public Map<String, Integer> countPerDay(String from, String to) throws SQLException {
        Map<String, Integer> perDay = new LinkedHashMap<String, Integer>();
        for (NamedCount day : query("SELECT substr(created_at, 1, 10) AS day, COUNT(*) AS n FROM search_logs "
                + "WHERE created_at >= ? AND created_at < ? GROUP BY day ORDER BY day",
                rs -> new NamedCount(0, rs.getString("day"), rs.getInt("n")), from, to)) {
            perDay.put(day.getLabel(), day.getCount());
        }
        return perDay;
    }

    /**
     * Medicines searched and found nowhere (results_count = 0, a medicine matched), grouped by
     * medicine + area, most failed searches first.
     */
    public List<ShortageRow> findShortages(String from, String to, int limit) throws SQLException {
        return query("SELECT s.medicine_id, m.brand_name, m.strength, a.name AS area_name, COUNT(*) AS failed "
                + "FROM search_logs s JOIN medicines m ON m.id = s.medicine_id "
                + "LEFT JOIN areas a ON a.id = s.area_id "
                + "WHERE s.results_count = 0 AND s.created_at >= ? AND s.created_at < ? "
                + "GROUP BY s.medicine_id, s.area_id ORDER BY failed DESC, m.brand_name LIMIT ?",
                rs -> new ShortageRow(rs.getInt("medicine_id"), rs.getString("brand_name") + " " + rs.getString("strength"),
                        rs.getString("area_name") == null ? "-" : rs.getString("area_name"), rs.getInt("failed")),
                from, to, limit);
    }
}
