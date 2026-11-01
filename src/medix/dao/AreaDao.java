package medix.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.Area;

/**
 * Data access for the areas table.
 */
public class AreaDao extends BaseDao<Area> {

    @Override
    protected String tableName() {
        return "areas";
    }

    @Override
    protected Area mapRow(ResultSet rs) throws SQLException {
        Area a = new Area();
        a.setId(rs.getInt("id"));
        a.setName(rs.getString("name"));
        a.setCity(rs.getString("city"));
        a.setLatitude(rs.getDouble("latitude"));
        a.setLongitude(rs.getDouble("longitude"));
        return a;
    }

    public int insert(Area a) throws SQLException {
        int id = executeInsert(
                "INSERT INTO areas (name, city, latitude, longitude) VALUES (?, ?, ?, ?)",
                a.getName(), a.getCity(), a.getLatitude(), a.getLongitude());
        a.setId(id);
        return id;
    }

    public boolean update(Area a) throws SQLException {
        return executeUpdate(
                "UPDATE areas SET name = ?, city = ?, latitude = ?, longitude = ? WHERE id = ?",
                a.getName(), a.getCity(), a.getLatitude(), a.getLongitude(), a.getId()) > 0;
    }

    public Area findByName(String name) throws SQLException {
        return findOne("SELECT * FROM areas WHERE name = ?", name);
    }

    /** All areas sorted by name, for combo boxes. */
    public List<Area> findAllByName() throws SQLException {
        return findList("SELECT * FROM areas ORDER BY name");
    }
}
