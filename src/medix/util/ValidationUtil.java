package medix.util;

import java.util.regex.Pattern;

/**
 * Input checks shared by the forms and the services.
 */
public final class ValidationUtil {

    public static final int MIN_PASSWORD_LENGTH = 6;

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    /** Bangladeshi mobile: 01 + operator digit 3-9 + 8 digits. */
    private static final Pattern BD_MOBILE = Pattern.compile("^01[3-9]\\d{8}$");
    private static final Pattern LICENSE_NO = Pattern.compile("^[A-Za-z0-9-]{6,30}$");

    private ValidationUtil() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Trimmed text, or null if blank. */
    public static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL.matcher(email.trim()).matches();
    }

    /** Lower-case, trimmed email for storing and comparing. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /** 01XXXXXXXXX; dashes and spaces are allowed and ignored, e.g. 01712-345678. */
    public static boolean isValidBdMobile(String phone) {
        return phone != null && BD_MOBILE.matcher(normalizePhone(phone)).matches();
    }

    /** Phone without dashes and spaces, e.g. "01712345678". */
    public static String normalizePhone(String phone) {
        return phone == null ? null : phone.replace("-", "").replace(" ", "").trim();
    }

    /**
     * Privacy mask: "01819123552" → "01819-…552" (first 5 and last 3 digits). Short or empty
     * numbers become "…".
     */
    public static String maskPhone(String phone) {
        String p = normalizePhone(phone);
        if (p == null || p.length() < 9) {
            return "…";
        }
        return p.substring(0, 5) + "-…" + p.substring(p.length() - 3);
    }

    public static boolean isValidLicenseNo(String licenseNo) {
        return licenseNo != null && LICENSE_NO.matcher(licenseNo.trim()).matches();
    }

    public static boolean isValidPassword(String password) {
        return password != null && password.length() >= MIN_PASSWORD_LENGTH;
    }

    /** Parses a decimal number; returns null for blank or invalid text. */
    public static Double parseDouble(String text) {
        if (isBlank(text)) {
            return null;
        }
        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
