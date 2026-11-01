package medix.model;

/**
 * A user with the name of their area (admin Users screen). Not a table: UserDao builds it with a JOIN.
 */
public class UserDetails {

    private final User user;
    private final String areaName;

    public UserDetails(User user, String areaName) {
        this.user = user;
        this.areaName = areaName;
    }

    public User getUser() {
        return user;
    }

    /** Empty when the user has no area. */
    public String getAreaName() {
        return areaName;
    }
}
