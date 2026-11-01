package medix.service;

/**
 * A business-rule problem whose message can be shown to the user as it is
 * (e.g. "This medicine is already in the catalog.").
 */
public class ServiceException extends Exception {

    public ServiceException(String message) {
        super(message);
    }
}
