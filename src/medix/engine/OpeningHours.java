package medix.engine;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * "Open now" from open_time / close_time (HH:mm). Handles hours past midnight,
 * e.g. 20:00 – 02:00. Equal times mean open 24 hours.
 */
public final class OpeningHours {

    private OpeningHours() {
    }

    public static boolean isOpenNow(String openTime, String closeTime) {
        return isOpen(openTime, closeTime, LocalTime.now());
    }

    /** False when a time is missing or not HH:mm. */
    public static boolean isOpen(String openTime, String closeTime, LocalTime at) {
        LocalTime open = parse(openTime);
        LocalTime close = parse(closeTime);
        if (open == null || close == null) {
            return false;
        }
        if (open.equals(close)) {
            return true;
        }
        if (open.isBefore(close)) {
            return !at.isBefore(open) && at.isBefore(close);
        }
        // closes after midnight
        return !at.isBefore(open) || at.isBefore(close);
    }

    private static LocalTime parse(String hhmm) {
        if (hhmm == null) {
            return null;
        }
        try {
            return LocalTime.parse(hhmm.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
