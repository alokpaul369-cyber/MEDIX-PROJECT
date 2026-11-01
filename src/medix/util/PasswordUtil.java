package medix.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing: SHA-256 over (salt bytes + password UTF-8 bytes) with a random
 * 16-byte salt per user. users.salt and users.password_hash store Base64 text.
 */
public final class PasswordUtil {

    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {
    }

    public static String generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(salt));
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static boolean verify(String password, String salt, String expectedHash) {
        if (password == null || salt == null || expectedHash == null) {
            return false;
        }
        byte[] actual = Base64.getDecoder().decode(hash(password, salt));
        byte[] expected = Base64.getDecoder().decode(expectedHash);
        return MessageDigest.isEqual(actual, expected);
    }
}
