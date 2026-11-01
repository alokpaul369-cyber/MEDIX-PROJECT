package medix.model;

/**
 * Summary of one database table, read from SQLite metadata (start-up schema check).
 */
public class TableInfo {

    private int id;
    private String name;
    private int columnCount;
    private String primaryKey;
    private int foreignKeyCount;
    private int indexedForeignKeyCount;
    private long rowCount;
    private boolean idRuleOk;

    public TableInfo() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getColumnCount() {
        return columnCount;
    }

    public void setColumnCount(int columnCount) {
        this.columnCount = columnCount;
    }

    public String getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(String primaryKey) {
        this.primaryKey = primaryKey;
    }

    public int getForeignKeyCount() {
        return foreignKeyCount;
    }

    public void setForeignKeyCount(int foreignKeyCount) {
        this.foreignKeyCount = foreignKeyCount;
    }

    public int getIndexedForeignKeyCount() {
        return indexedForeignKeyCount;
    }

    public void setIndexedForeignKeyCount(int indexedForeignKeyCount) {
        this.indexedForeignKeyCount = indexedForeignKeyCount;
    }

    public long getRowCount() {
        return rowCount;
    }

    public void setRowCount(long rowCount) {
        this.rowCount = rowCount;
    }

    /** True when the FIRST column is "id INTEGER PRIMARY KEY AUTOINCREMENT" (the ID rule). */
    public boolean isIdRuleOk() {
        return idRuleOk;
    }

    public void setIdRuleOk(boolean idRuleOk) {
        this.idRuleOk = idRuleOk;
    }

    /** Text for the "FK index" column, e.g. "2/2 indexed" or "no FKs". */
    public String getForeignKeyIndexText() {
        if (foreignKeyCount == 0) {
            return "no FKs";
        }
        return indexedForeignKeyCount + "/" + foreignKeyCount + " indexed";
    }

    @Override
    public String toString() {
        return name;
    }
}
