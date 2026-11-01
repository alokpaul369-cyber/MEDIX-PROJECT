package medix.model;

/**
 * Row of the notifications table.
 */
public class Notification {

    private int id;
    private int userId;
    private NotificationType type;
    private String message;
    private String createdAt;
    private boolean read;
    /** What the notification is about, for "Open": "inventory", "reservations", "pharmacies", "reports" or null. */
    private String linkEntity;
    private Integer linkId;

    public String getLinkEntity() {
        return linkEntity;
    }

    public void setLinkEntity(String linkEntity) {
        this.linkEntity = linkEntity;
    }

    public Integer getLinkId() {
        return linkId;
    }

    public void setLinkId(Integer linkId) {
        this.linkId = linkId;
    }

    public Notification() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public NotificationType getType() {
        return type;
    }

    public void setType(NotificationType type) {
        this.type = type;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }
}
