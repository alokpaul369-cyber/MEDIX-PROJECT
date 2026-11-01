package medix.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import medix.dao.SchemaDao;
import medix.model.TableInfo;
import medix.util.AppPaths;
import medix.util.ErrorLog;

/**
 * Creates the database on start-up and answers questions about its structure.
 */
public class DatabaseService {

    public static final int EXPECTED_TABLE_COUNT = 12;
    private static final String SCHEMA_RESOURCE = "/medix/resources/schema.sql";

    private final SchemaDao schemaDao = new SchemaDao();

    /**
     * Makes sure data/ and data/licenses/ exist, runs schema.sql
     * (CREATE ... IF NOT EXISTS, so it is safe every start) and seeds first-run data.
     */
    public void initialize() throws SQLException, IOException {
        AppPaths.getDataDir();
        AppPaths.getLicensesDir();
        migrate();
        schemaDao.executeScript(loadStatements());
        new SeedService().seedIfNeeded();
    }

    /**
     * Changes for databases created by an earlier part. Runs before schema.sql.
     * Part 3: medicines.form gained 'POWDER'. SQLite cannot change a CHECK constraint, so an
     * EMPTY medicines table (no medicine could be added before Part 3) is dropped and
     * schema.sql re-creates it with the new constraint.
     */
    private void migrate() throws SQLException {
        String medicinesSql = schemaDao.getTableSql("medicines");
        if (medicinesSql != null && !medicinesSql.contains("'POWDER'")) {
            if (schemaDao.countRows("medicines") == 0) {
                schemaDao.executeScript(java.util.Collections.singletonList("DROP TABLE medicines"));
            } else {
                ErrorLog.log("medicines table has rows; the POWDER form cannot be added automatically.");
            }
        }
        // Part 4: one-time data fix (PRAGMA user_version 0 → 1). Pharmacies registered in
        // Parts 2-3 got trust_score 0 instead of the default 80; nothing lowers it yet.
        if (schemaDao.getTableSql("pharmacies") != null && schemaDao.getUserVersion() < 1) {
            schemaDao.executeScript(java.util.Arrays.asList(
                    "UPDATE pharmacies SET trust_score = 80 WHERE trust_score = 0",
                    "PRAGMA user_version = 1"));
        }
        // Part 4: stock_movements.note (why stock was added, sold or adjusted)
        addColumnIfMissing("stock_movements", "note", "note TEXT");
        // Part 7: extend-once flag, who cancelled and why, nightly stale-stock trust penalty
        addColumnIfMissing("reservations", "extended",
                "extended INTEGER NOT NULL DEFAULT 0 CHECK (extended IN (0, 1))");
        addColumnIfMissing("reservations", "cancelled_by",
                "cancelled_by TEXT CHECK (cancelled_by IN ('PATIENT', 'PHARMACY', 'SYSTEM'))");
        addColumnIfMissing("reservations", "cancel_reason", "cancel_reason TEXT");
        addColumnIfMissing("pharmacies", "stale_penalty",
                "stale_penalty INTEGER NOT NULL DEFAULT 0 CHECK (stale_penalty >= 0)");
        // Part 8: what a notification is about, so "Open" can show the batch / reservation
        addColumnIfMissing("notifications", "link_entity", "link_entity TEXT");
        addColumnIfMissing("notifications", "link_id", "link_id INTEGER");
    }

    /** ALTER TABLE … ADD COLUMN when an existing table does not have the column yet. */
    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        String sql = schemaDao.getTableSql(table);
        if (sql != null && !schemaDao.hasColumn(table, column)) {
            schemaDao.executeScript(java.util.Collections.singletonList(
                    "ALTER TABLE " + table + " ADD COLUMN " + definition));
        }
    }

    public List<TableInfo> getTables() throws SQLException {
        return schemaDao.findTables();
    }

    /**
     * Start-up check of the ID rule and the foreign-key indexes, read from sqlite_master and
     * PRAGMA table_info / foreign_key_list / index_list. Returns the lines Main prints, e.g.
     * "Schema check: 12/12 tables use id INTEGER PRIMARY KEY AUTOINCREMENT"; problems are named.
     */
    public List<String> checkSchema() throws SQLException {
        List<TableInfo> tables = getTables();
        int idOk = 0;
        int fks = 0;
        int indexed = 0;
        List<String> lines = new ArrayList<String>();
        List<String> problems = new ArrayList<String>();
        for (TableInfo t : tables) {
            if (t.isIdRuleOk()) {
                idOk++;
            } else {
                problems.add("  " + t.getName() + ": first column is not id INTEGER PRIMARY KEY AUTOINCREMENT");
            }
            fks += t.getForeignKeyCount();
            indexed += t.getIndexedForeignKeyCount();
            if (t.getIndexedForeignKeyCount() < t.getForeignKeyCount()) {
                problems.add("  " + t.getName() + ": " + t.getForeignKeyIndexText() + " foreign key columns");
            }
        }
        lines.add("Schema check: " + idOk + "/" + tables.size() + " tables use id INTEGER PRIMARY KEY AUTOINCREMENT"
                + (tables.size() == EXPECTED_TABLE_COUNT ? "" : " (expected " + EXPECTED_TABLE_COUNT + " tables)"));
        lines.add("Schema check: " + indexed + "/" + fks + " foreign key columns have an index");
        lines.addAll(problems);
        return lines;
    }

    /** True when every table follows the ID rule, every FK is indexed and there are 12 tables. */
    public boolean isSchemaOk() throws SQLException {
        List<TableInfo> tables = getTables();
        for (TableInfo t : tables) {
            if (!t.isIdRuleOk() || t.getIndexedForeignKeyCount() < t.getForeignKeyCount()) {
                return false;
            }
        }
        return tables.size() == EXPECTED_TABLE_COUNT;
    }

    /** Reads schema.sql from the classpath and splits it into statements on ';'. */
    private List<String> loadStatements() throws IOException {
        InputStream in = DatabaseService.class.getResourceAsStream(SCHEMA_RESOURCE);
        if (in == null) {
            throw new IOException("Missing resource " + SCHEMA_RESOURCE);
        }
        StringBuilder sql = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().startsWith("--")) {
                    sql.append(line).append('\n');
                }
            }
        }
        List<String> statements = new ArrayList<String>();
        for (String part : sql.toString().split(";")) {
            if (!part.trim().isEmpty()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }
}
