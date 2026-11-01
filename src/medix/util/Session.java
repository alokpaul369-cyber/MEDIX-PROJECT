package medix.util;

import medix.model.Pharmacy;
import medix.model.Role;
import medix.model.User;

/**
 * Holds who is logged in. Set by AuthService.login, cleared on logout.
 */
public final class Session {

    private static volatile User user;
    private static volatile Pharmacy pharmacy;

    private Session() {
    }

    /** pharmacy is the user's pharmacy for PHARMACY users, otherwise null. */
    public static void login(User loggedIn, Pharmacy ownPharmacy) {
        user = loggedIn;
        pharmacy = ownPharmacy;
    }

    public static void logout() {
        user = null;
        pharmacy = null;
    }

    public static User getUser() {
        return user;
    }

    /** The logged-in pharmacy user's pharmacy; null for patients, admins and guests. */
    public static Pharmacy getPharmacy() {
        return pharmacy;
    }

    /** Replace the cached pharmacy after it was edited. */
    public static void setPharmacy(Pharmacy updated) {
        pharmacy = updated;
    }

    public static boolean isLoggedIn() {
        return user != null;
    }

    /** Id of the logged-in user, or null for a guest. */
    public static Integer getUserId() {
        return user == null ? null : user.getId();
    }

    public static Role getRole() {
        return user == null ? null : user.getRole();
    }

    public static boolean hasRole(Role role) {
        return user != null && user.getRole() == role;
    }
}
