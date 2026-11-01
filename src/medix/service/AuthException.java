package medix.service;

/**
 * A login or registration problem whose message can be shown to the user as it is.
 */
public class AuthException extends Exception {

    public AuthException(String message) {
        super(message);
    }
}
