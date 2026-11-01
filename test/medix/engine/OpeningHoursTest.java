package medix.engine;

import java.time.LocalTime;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpeningHoursTest {

    @Test
    public void normalDay() {
        assertTrue(OpeningHours.isOpen("08:00", "23:00", LocalTime.of(8, 0)));
        assertTrue(OpeningHours.isOpen("08:00", "23:00", LocalTime.of(22, 59)));
        assertFalse(OpeningHours.isOpen("08:00", "23:00", LocalTime.of(23, 0)));
        assertFalse(OpeningHours.isOpen("08:00", "23:00", LocalTime.of(7, 30)));
    }

    @Test
    public void pastMidnight() {
        assertTrue(OpeningHours.isOpen("20:00", "02:00", LocalTime.of(23, 30)));
        assertTrue(OpeningHours.isOpen("20:00", "02:00", LocalTime.of(1, 15)));
        assertFalse(OpeningHours.isOpen("20:00", "02:00", LocalTime.of(3, 0)));
        assertFalse(OpeningHours.isOpen("20:00", "02:00", LocalTime.of(12, 0)));
    }

    @Test
    public void sameTimesMeanAllDayAndBadTimesMeanClosed() {
        assertTrue(OpeningHours.isOpen("00:00", "00:00", LocalTime.of(4, 0)));
        assertFalse(OpeningHours.isOpen(null, "22:00", LocalTime.of(12, 0)));
        assertFalse(OpeningHours.isOpen("9am", "22:00", LocalTime.of(12, 0)));
    }
}
