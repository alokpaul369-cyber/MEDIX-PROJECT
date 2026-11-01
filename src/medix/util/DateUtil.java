package medix.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Date helpers. The database stores dates as text:
 * date = yyyy-MM-dd, date-time = yyyy-MM-dd HH:mm:ss.
 */
public final class DateUtil {

    public static final String DATE_PATTERN = "yyyy-MM-dd";
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String DISPLAY_DATE_TIME_PATTERN = "dd MMM yyyy, hh:mm a";

    private DateUtil() {
    }

    /** Current date-time as yyyy-MM-dd HH:mm:ss. */
    public static String now() {
        return formatDateTime(new Date());
    }

    /** Today as yyyy-MM-dd. */
    public static String today() {
        return formatDate(new Date());
    }

    public static String formatDate(Date date) {
        return date == null ? null : new SimpleDateFormat(DATE_PATTERN).format(date);
    }

    public static String formatDateTime(Date date) {
        return date == null ? null : new SimpleDateFormat(DATE_TIME_PATTERN).format(date);
    }

    /** Parses yyyy-MM-dd; returns null for null/empty/invalid text. */
    public static Date parseDate(String text) {
        return parse(text, DATE_PATTERN);
    }

    /** Parses yyyy-MM-dd HH:mm:ss; returns null for null/empty/invalid text. */
    public static Date parseDateTime(String text) {
        return parse(text, DATE_TIME_PATTERN);
    }

    /** yyyy-MM-dd HH:mm:ss text shown as e.g. "05 Nov 2026, 02:30 PM". */
    public static String toDisplay(String dateTime) {
        Date d = parseDateTime(dateTime);
        return d == null ? "" : new SimpleDateFormat(DISPLAY_DATE_TIME_PATTERN).format(d);
    }

    /** yyyy-MM-dd or yyyy-MM-dd HH:mm:ss text shown as e.g. "05 Nov 2026"; "" if empty. */
    public static String toDisplayDate(String dateOrDateTime) {
        if (dateOrDateTime == null || dateOrDateTime.length() < 10) {
            return "";
        }
        Date d = parseDate(dateOrDateTime.substring(0, 10));
        return d == null ? dateOrDateTime : new SimpleDateFormat("dd MMM yyyy").format(d);
    }

    /** Time since a yyyy-MM-dd HH:mm:ss date-time: "now", "25 min", "2 h", "3 d"; "" if empty. */
    public static String ago(String dateTime) {
        Date d = parseDateTime(dateTime);
        if (d == null) {
            return "";
        }
        long minutes = (System.currentTimeMillis() - d.getTime()) / 60000L;
        if (minutes < 1) {
            return "now";
        }
        if (minutes < 60) {
            return minutes + " min";
        }
        if (minutes < 24 * 60) {
            return (minutes / 60) + " h";
        }
        return (minutes / (24 * 60)) + " d";
    }

    /** True if the yyyy-MM-dd HH:mm:ss date-time is at least the given hours in the past. */
    public static boolean isOlderThanHours(String dateTime, int hours) {
        Date d = parseDateTime(dateTime);
        return d != null && System.currentTimeMillis() - d.getTime() >= hours * 3600L * 1000L;
    }

    /** True if the yyyy-MM-dd HH:mm:ss date-time is more than the given months in the past. */
    public static boolean isOlderThanMonths(String dateTime, int months) {
        Date d = parseDateTime(dateTime);
        if (d == null) {
            return false;
        }
        Calendar limit = Calendar.getInstance();
        limit.add(Calendar.MONTH, -months);
        return d.before(limit.getTime());
    }

    /** Date-time that is the given number of minutes from now. */
    public static String nowPlusMinutes(int minutes) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, minutes);
        return formatDateTime(c.getTime());
    }

    /** Date that is the given number of days from today. */
    public static String todayPlusDays(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, days);
        return formatDate(c.getTime());
    }

    /** Whole days from today until the yyyy-MM-dd date (negative if it is in the past). */
    public static long daysUntil(String date) {
        Date target = parseDate(date);
        Date today = parseDate(today());
        if (target == null || today == null) {
            return 0;
        }
        long diff = target.getTime() - today.getTime();
        return Math.round(diff / (24.0 * 60 * 60 * 1000));
    }

    /** Opening hours used when the field is left blank. */
    public static final String DEFAULT_OPEN_TIME = "09:00";
    public static final String DEFAULT_CLOSE_TIME = "22:00";

    /** "h:mm AM – h:mm PM" with "-", "–" or "—" between, spaces optional. */
    private static final Pattern OPENING_HOURS = Pattern.compile(
            "^\\s*(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])\\s*[-–—]\\s*(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])\\s*$");

    /**
     * Parses opening hours such as "8:00 AM – 11:00 PM" into {"08:00", "23:00"} (HH:mm).
     * Blank text gives the default 09:00 – 22:00. Returns null if the format is wrong.
     */
    public static String[] parseOpeningHours(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new String[]{DEFAULT_OPEN_TIME, DEFAULT_CLOSE_TIME};
        }
        Matcher m = OPENING_HOURS.matcher(text);
        if (!m.matches()) {
            return null;
        }
        String open = to24Hour(m.group(1), m.group(2), m.group(3));
        String close = to24Hour(m.group(4), m.group(5), m.group(6));
        if (open == null || close == null) {
            return null;
        }
        return new String[]{open, close};
    }

    /** "8:00 AM – 11:00 PM", or "Open 24 hours" when open and close are the same time. */
    public static String formatOpeningHours(String openTime, String closeTime) {
        if (openTime != null && openTime.equals(closeTime)) {
            return "Open 24 hours";
        }
        return formatTime12(openTime) + " – " + formatTime12(closeTime);
    }

    /** "08:00" → "8:00 AM", "23:00" → "11:00 PM". Returns the input unchanged if it is not HH:mm. */
    public static String formatTime12(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{2}:\\d{2}")) {
            return hhmm;
        }
        int h = Integer.parseInt(hhmm.substring(0, 2));
        String suffix = h < 12 ? "AM" : "PM";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + ":" + hhmm.substring(3) + " " + suffix;
    }

    private static String to24Hour(String hourText, String minuteText, String amPm) {
        int hour = Integer.parseInt(hourText);
        int minute = Integer.parseInt(minuteText);
        if (hour < 1 || hour > 12 || minute > 59) {
            return null;
        }
        boolean pm = amPm.equalsIgnoreCase("PM");
        int h24 = hour % 12 + (pm ? 12 : 0);
        return String.format("%02d:%02d", h24, minute);
    }

    private static Date parse(String text, String pattern) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        SimpleDateFormat f = new SimpleDateFormat(pattern);
        f.setLenient(false);
        try {
            return f.parse(text.trim());
        } catch (ParseException e) {
            return null;
        }
    }
}
