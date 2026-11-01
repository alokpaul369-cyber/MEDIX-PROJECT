package medix.engine;

import java.util.ArrayList;
import medix.model.PharmacyStock;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class AvailabilityCacheTest {

    @After
    public void clear() {
        AvailabilityCache.clear();
    }

    @Test
    public void keyIncludesMedicineAreaAndRadius() {
        assertEquals("5:1:5.0", AvailabilityCache.key(5, 1, 5.0));
        assertEquals("5:1:any", AvailabilityCache.key(5, 1, null));
    }

    @Test
    public void invalidateRemovesOnlyThatMedicine() {
        AvailabilityCache.put(AvailabilityCache.key(5, 1, 5.0), new ArrayList<PharmacyStock>());
        AvailabilityCache.put(AvailabilityCache.key(5, 2, null), new ArrayList<PharmacyStock>());
        AvailabilityCache.put(AvailabilityCache.key(55, 1, 5.0), new ArrayList<PharmacyStock>());
        AvailabilityCache.invalidateMedicine(5);
        assertNull(AvailabilityCache.get(AvailabilityCache.key(5, 1, 5.0)));
        assertNull(AvailabilityCache.get(AvailabilityCache.key(5, 2, null)));
        assertNotNull("medicine 55 must stay", AvailabilityCache.get(AvailabilityCache.key(55, 1, 5.0)));
    }

    @Test
    public void keepsAtMost200LeastRecentlyUsedEntries() {
        for (int i = 0; i < AvailabilityCache.MAX_ENTRIES; i++) {
            AvailabilityCache.put(AvailabilityCache.key(i, 1, 5.0), new ArrayList<PharmacyStock>());
        }
        AvailabilityCache.get(AvailabilityCache.key(0, 1, 5.0)); // 0 is now recently used
        AvailabilityCache.put(AvailabilityCache.key(999, 1, 5.0), new ArrayList<PharmacyStock>());
        assertEquals(AvailabilityCache.MAX_ENTRIES, AvailabilityCache.size());
        assertNotNull(AvailabilityCache.get(AvailabilityCache.key(0, 1, 5.0)));
        assertNull("the least recently used entry is dropped", AvailabilityCache.get(AvailabilityCache.key(1, 1, 5.0)));
    }
}
