package medix.util;

import java.awt.Component;
import java.util.List;
import java.util.function.IntFunction;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import medix.model.JobRun;
import medix.model.JobRunSummary;
import medix.model.Notification;
import medix.model.NotificationType;

/**
 * The notification table shared by AlertsFrame (pharmacies) and NotificationFrame (patients,
 * admins): ID | Type | Message | Time | Status, unread rows bold, the type as a coloured label.
 * Also the jobs table of AlertsFrame: ID | Job | Runs | Last run | Result.
 */
public final class NotificationTableUtil {

    public static final int COL_TYPE = 1;
    public static final String ALL_TYPES = "All types";

    private NotificationTableUtil() {
    }

    /** cmbFilterType items: "All types" then every type label. */
    public static String[] filterItems() {
        NotificationType[] types = NotificationType.values();
        String[] items = new String[types.length + 1];
        items[0] = ALL_TYPES;
        for (int i = 0; i < types.length; i++) {
            items[i + 1] = types[i].toString();
        }
        return items;
    }

    /** The type for a cmbFilterType index (0 = all → null). */
    public static NotificationType typeAt(int index) {
        return index <= 0 ? null : NotificationType.values()[index - 1];
    }

    /** rowAt gives the notification of a table-model row. */
    public static DefaultTableModel setup(JTable table, final IntFunction<Notification> rowAt) {
        DefaultTableModel model = TableUtil.setupTable(table, "ID", "Type", "Message", "Time", "Status");
        TableUtil.setColumnWidth(table, COL_TYPE, 110);
        TableUtil.setColumnWidth(table, 2, 560);
        TableUtil.setColumnWidth(table, 3, 160);
        TableUtil.setColumnWidth(table, 4, 70);
        TableUtil.centerColumn(table, 4);
        table.getColumnModel().getColumn(COL_TYPE).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                    int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                Notification n = rowAt.apply(t.convertRowIndexToModel(row));
                setFont(UITheme.FONT_BODY_BOLD);
                if (!selected) {
                    setForeground(UITheme.notificationTypeColor(n.getType(), n.getMessage()));
                }
                return this;
            }
        });
        TableUtil.setRowBold(table, modelRow -> !rowAt.apply(modelRow).isRead());
        return model;
    }

    public static void fill(DefaultTableModel model, List<Notification> list) {
        TableUtil.clear(model);
        for (Notification n : list) {
            model.addRow(new Object[]{n.getId(), n.getType(), n.getMessage(), when(n.getCreatedAt()),
                n.isRead() ? "Read" : "Unread"});
        }
    }

    /** "17:27 (25 min ago)" today, otherwise "01 Nov 2026, 05:27 PM". */
    public static String when(String createdAt) {
        if (createdAt != null && createdAt.startsWith(DateUtil.today())) {
            String ago = DateUtil.ago(createdAt);
            return createdAt.substring(11, 16) + ("now".equals(ago) ? " (just now)" : " (" + ago + " ago)");
        }
        return DateUtil.toDisplay(createdAt);
    }

    // ---------------------------------------------------------------- jobs

    public static DefaultTableModel setupJobs(JTable table, final IntFunction<JobRunSummary> rowAt) {
        DefaultTableModel model = TableUtil.setupTable(table, "ID", "Job", "Runs", "Last run", "Result");
        TableUtil.setColumnWidth(table, 1, 190);
        TableUtil.setColumnWidth(table, 2, 110);
        TableUtil.setColumnWidth(table, 3, 170);
        TableUtil.setColumnWidth(table, 4, 430);
        table.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                    int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                JobRun last = rowAt.apply(t.convertRowIndexToModel(row)).getLast();
                setFont(UITheme.FONT_BODY_BOLD);
                if (!selected) {
                    setForeground(last.isOk() ? UITheme.SUCCESS : UITheme.DANGER);
                }
                return this;
            }
        });
        return model;
    }

    public static void fillJobs(DefaultTableModel model, List<JobRunSummary> jobs) {
        TableUtil.clear(model);
        for (JobRunSummary s : jobs) {
            JobRun last = s.getLast();
            model.addRow(new Object[]{last.getId(), last.getJobName(),
                s.getRuns() + (s.getFailures() > 0 ? " (" + s.getFailures() + " failed)" : ""),
                when(last.getStartedAt()),
                last.isOk() ? "OK · " + last.getItemsAffected() + " item(s)"
                : "FAILED: " + (last.getMessage() == null ? "" : last.getMessage())});
        }
    }
}
