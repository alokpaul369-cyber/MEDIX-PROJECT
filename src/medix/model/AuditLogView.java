package medix.model;

/**
 * An audit_logs row with the name of the user who did it (admin Audit Log screen).
 * Not a table: AuditLogDao builds it with a JOIN.
 */
public class AuditLogView {

    private final AuditLog log;
    private final String userName;

    public AuditLogView(AuditLog log, String userName) {
        this.log = log;
        this.userName = userName;
    }

    public AuditLog getLog() {
        return log;
    }

    /** "System" when no user is linked (background jobs, failed logins). */
    public String getUserName() {
        return userName;
    }
}
