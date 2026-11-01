package medix.util;

import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * SHA-256 over salt + password, salt and hash stored as Base64.
 */
public class PasswordUtilTest {

    @Test
    public void saltIsSixteenRandomBytes() {
        String a = PasswordUtil.generateSalt();
        String b = PasswordUtil.generateSalt();
        assertEquals(16, Base64.getDecoder().decode(a).length);
        assertNotEquals("every user gets an own salt", a, b);
    }

    @Test
    public void hashIsRepeatableAndDependsOnTheSalt() {
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash("admin123", salt);
        assertEquals(hash, PasswordUtil.hash("admin123", salt));
        assertEquals("SHA-256 = 32 bytes", 32, Base64.getDecoder().decode(hash).length);
        assertNotEquals("same password, other salt: other hash", hash, PasswordUtil.hash("admin123",
                PasswordUtil.generateSalt()));
        assertFalse("the plain password is never stored", hash.contains("admin123"));
    }

    @Test
    public void verifyAcceptsOnlyTheRightPassword() {
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash("demo123", salt);
        assertTrue(PasswordUtil.verify("demo123", salt, hash));
        assertFalse(PasswordUtil.verify("Demo123", salt, hash));
        assertFalse(PasswordUtil.verify("demo1234", salt, hash));
        assertFalse(PasswordUtil.verify("", salt, hash));
        assertFalse(PasswordUtil.verify(null, salt, hash));
        assertFalse(PasswordUtil.verify("demo123", null, hash));
    }

    @Test
    public void unicodePasswordsWork() {
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash("পাসওয়ার্ড-১২৩", salt);
        assertTrue(PasswordUtil.verify("পাসওয়ার্ড-১২৩", salt, hash));
    }
}
