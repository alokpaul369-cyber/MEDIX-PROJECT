package medix.util;

import java.awt.Color;
import java.awt.Component;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

/**
 * JTable helpers. Every table that shows database rows has "ID" as its first
 * column: narrow and centered.
 *
 * <pre>
 * DefaultTableModel model = TableUtil.setupTable(tblInventory, "ID", "Medicine", "Qty");
 * model.addRow(new Object[]{inv.getId(), name, inv.getQuantity()});
 * Integer id = TableUtil.getSelectedId(tblInventory);
 * </pre>
 */
public final class TableUtil {

    public static final int ROW_HEIGHT = 28;
    public static final int ID_COLUMN_WIDTH = 50;

    private TableUtil() {
    }

    /**
     * Gives the table a read-only model with the given columns and applies the MediX style.
     * The first column must be "ID".
     */
    public static DefaultTableModel setupTable(JTable table, String... columns) {
        if (columns.length == 0 || !"ID".equals(columns[0])) {
            throw new IllegalArgumentException("The first column of every MediX table must be \"ID\"");
        }
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int column) {
                for (int row = 0; row < getRowCount(); row++) {
                    Object value = getValueAt(row, column);
                    if (value != null) {
                        return value.getClass();
                    }
                }
                return Object.class;
            }
        };
        table.setModel(model);
        table.setAutoCreateRowSorter(true);
        styleTable(table);
        return model;
    }

    /** Row height, bold header, accent selection and the narrow centered ID column. */
    public static void styleTable(JTable table) {
        table.setRowHeight(ROW_HEIGHT);
        table.setFont(UITheme.FONT_BODY);
        table.setSelectionBackground(UITheme.ACCENT);
        table.setSelectionForeground(UITheme.WHITE);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);

        JTableHeader header = table.getTableHeader();
        header.setFont(UITheme.FONT_BODY_BOLD);

        if (table.getColumnCount() > 0) {
            styleIdColumn(table.getColumnModel().getColumn(0));
        }
    }

    /** Id of the selected row (read from column 0), or null when nothing is selected. */
    public static Integer getSelectedId(JTable table) {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        Object value = table.getModel().getValueAt(modelRow, 0);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return value == null ? null : Integer.valueOf(value.toString());
    }

    public static void clear(DefaultTableModel model) {
        model.setRowCount(0);
    }

    /** Sets a column's preferred width (use for columns other than ID). */
    public static void setColumnWidth(JTable table, int column, int width) {
        table.getColumnModel().getColumn(column).setPreferredWidth(width);
    }

    /** Centers the text of any column. */
    public static void centerColumn(JTable table, int column) {
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        table.getColumnModel().getColumn(column).setCellRenderer(center);
    }

    /**
     * Gives whole rows a background colour, e.g. yellow for rows that need attention.
     * colorForModelRow returns null for the normal background. Call it AFTER all other
     * renderers are set: it wraps the renderer of every column.
     */
    public static void setRowBackground(JTable table, final IntFunction<Color> colorForModelRow) {
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn col = table.getColumnModel().getColumn(i);
            TableCellRenderer base = col.getCellRenderer();
            if (base == null) {
                base = table.getDefaultRenderer(table.getColumnClass(i));
            }
            final TableCellRenderer delegate = base;
            col.setCellRenderer((t, value, selected, focus, row, column) -> {
                if (delegate instanceof DefaultTableCellRenderer) {
                    // setBackground() is remembered by DefaultTableCellRenderer; clear the
                    // previous row's colour so it does not leak into this row
                    ((DefaultTableCellRenderer) delegate).setBackground(null);
                }
                Component c = delegate.getTableCellRendererComponent(t, value, selected, focus, row, column);
                if (!selected) {
                    Color bg = colorForModelRow.apply(t.convertRowIndexToModel(row));
                    if (bg != null) {
                        c.setBackground(bg);
                    }
                }
                return c;
            });
        }
    }

    /**
     * Makes whole rows bold (e.g. unread notifications). Call after the other renderers are set;
     * it wraps the renderer of every column, like setRowBackground.
     */
    public static void setRowBold(JTable table, final IntPredicate boldForModelRow) {
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn col = table.getColumnModel().getColumn(i);
            TableCellRenderer base = col.getCellRenderer();
            if (base == null) {
                base = table.getDefaultRenderer(table.getColumnClass(i));
            }
            final TableCellRenderer delegate = base;
            col.setCellRenderer((t, value, selected, focus, row, column) -> {
                Component c = delegate.getTableCellRendererComponent(t, value, selected, focus, row, column);
                // DefaultTableCellRenderer resets the font on every call, so only bold rows need work
                if (boldForModelRow.test(t.convertRowIndexToModel(row))) {
                    c.setFont(c.getFont().deriveFont(java.awt.Font.BOLD));
                }
                return c;
            });
        }
    }

    /** Selects the row whose ID (column 0) equals id; returns false if it is not shown. */
    public static boolean selectId(JTable table, int id) {
        for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) {
            Object value = table.getModel().getValueAt(table.convertRowIndexToModel(viewRow), 0);
            if (value instanceof Number && ((Number) value).intValue() == id) {
                table.setRowSelectionInterval(viewRow, viewRow);
                table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
                return true;
            }
        }
        return false;
    }

    private static void styleIdColumn(TableColumn col) {
        col.setMinWidth(40);
        col.setPreferredWidth(ID_COLUMN_WIDTH);
        col.setMaxWidth(70);
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        col.setCellRenderer(center);
    }
}
