package medix.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ValidationUtilTest {

    @Test
    public void masksPhoneUntilConfirmed() {
        assertEquals("01819-…552", ValidationUtil.maskPhone("01819123552"));
        assertEquals("01819-…552", ValidationUtil.maskPhone("01819-123552"));
        assertEquals("…", ValidationUtil.maskPhone(null));
        assertEquals("…", ValidationUtil.maskPhone("123"));
    }

    @Test
    public void bangladeshiMobileNumbers() {
        assertTrue(ValidationUtil.isValidBdMobile("01712345678"));
        assertTrue(ValidationUtil.isValidBdMobile("01712-345678"));
        assertFalse(ValidationUtil.isValidBdMobile("01212345678"));
        assertFalse(ValidationUtil.isValidBdMobile("0171234567"));
    }
}
