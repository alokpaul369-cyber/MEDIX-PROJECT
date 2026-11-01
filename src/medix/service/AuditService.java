package medix.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import medix.dao.AuditLogDao;
import medix.model.AuditLog;
import medix.model.AuditLogView;
import medix.model.NamedCount;
import medix.model.Role;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.Session;

/**
 * Writes audit_logs rows. Logging never stops the action being logged:
 * a failed write is printed and ignored.
 */
public class AuditService {

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String SIGNUP = "SIGNUP";

    private final AuditLogDao auditLogDao = new AuditLogDao();

    /** Logs an action by the logged-in user (or a guest when nobody is logged in). */
    public void log(String action, String entity, Integer entityId, String details) {
        logAs(Session.getUserId(), action, entity, entityId, details);
    }

    /** Logs an action for a specific user, e.g. SIGNUP before the user has logged in. */
    public void logAs(Integer userId, String action, String entity, Integer entityId, String details) {
        AuditLog a = new AuditLog();
        a.setUserId(userId);
        a.setAction(action);
        a.setEntity(entity);
        a.setEntityId(entityId);
        a.setDetails(details);
        try {
            auditLogDao.insert(a);
        } catch (SQLException e) {
            ErrorLog.log("Audit log failed (" + action + ")", e);
        }
    }

    // ---------------------------------------------------------------- admin Audit Log screen

    /** user filter value for entries without a user (background jobs, failed logins). */
    public static final int SYSTEM_USER = 0;
    /** The screen shows at most this many entries (newest). */
    public static final int MAX_ROWS = 2000;

    /**
     * Entries newest first (at most MAX_ROWS). userId null = everyone, SYSTEM_USER = no user;
     * action null = any; from / to are whole days (inclusive), null = open.
     */
    public List<AuditLogView> search(Integer userId, String action, Date from, Date to)
            throws ServiceException, SQLException {
        requireAdmin();
        return auditLogDao.search(userId, action, dayStart(from), dayAfter(to), MAX_ROWS);
    }

    /** How many entries match (to say "showing 2000 of 5321"). */
    public int count(Integer userId, String action, Date from, Date to) throws ServiceException, SQLException {
        requireAdmin();
        return auditLogDao.count(userId, action, dayStart(from), dayAfter(to));
    }

    public List<String> getActions() throws ServiceException, SQLException {
        requireAdmin();
        return auditLogDao.findActions();
    }

    /** Users that appear in the log: id + "Name (email)". */
    public List<NamedCount> getLoggedUsers() throws ServiceException, SQLException {
        requireAdmin();
        return auditLogDao.findLoggedUsers();
    }

    /**
     * Writes the rows as CSV (UTF-8 with BOM so Excel shows "–" and "→" correctly) and logs
     * AUDIT_LOG_EXPORTED.
     */
    public void exportCsv(List<AuditLogView> rows, File file) throws ServiceException, IOException {
        requireAdmin();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write('﻿');
            w.write("ID,Time,User,Action,Entity,Entity ID,Details\r\n");
            for (AuditLogView v : rows) {
                AuditLog a = v.getLog();
                w.write(a.getId() + "," + csv(a.getCreatedAt()) + "," + csv(v.getUserName()) + "," + csv(a.getAction())
                        + "," + csv(a.getEntity()) + "," + (a.getEntityId() == null ? "" : a.getEntityId()) + ","
                        + csv(a.getDetails()) + "\r\n");
            }
        }
        log("AUDIT_LOG_EXPORTED", null, null, rows.size() + " row(s) to " + file.getAbsolutePath());
    }

    /** One CSV field: quoted when it contains a comma, quote or line break. */
    static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String dayStart(Date day) {
        return day == null ? null : DateUtil.formatDate(day) + " 00:00:00";
    }

    private static String dayAfter(Date day) {
        if (day == null) {
            return null;
        }
        Calendar c = Calendar.getInstance();
        c.setTime(day);
        c.add(Calendar.DAY_OF_MONTH, 1);
        return DateUtil.formatDate(c.getTime()) + " 00:00:00";
    }

    private static void requireAdmin() throws ServiceException {
        if (!Session.hasRole(Role.ADMIN)) {
            throw new ServiceException("Only an admin can do this.");
        }
    }
}
