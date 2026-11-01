package medix.model;

/**
 * A label with a count, e.g. a medicine and how often it was searched (dashboard charts).
 * Not a table.
 */
public class NamedCount {

    private final int id;
    private final String label;
    private final int count;

    public NamedCount(int id, String label, int count) {
        this.id = id;
        this.label = label;
        this.count = count;
    }

    public int getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public int getCount() {
        return count;
    }
}
