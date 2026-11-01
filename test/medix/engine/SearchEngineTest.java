package medix.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import medix.model.Area;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.model.PharmacyStock;
import medix.util.DateUtil;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Name matching and pharmacy ranking without a database. */
public class SearchEngineTest {

    private static final List<Medicine> CATALOG = Arrays.asList(
            med(1, "Napa", "Paracetamol", "500 mg", MedicineForm.TABLET),
            med(2, "Napa Extra", "Paracetamol + Caffeine", "500 mg + 65 mg", MedicineForm.TABLET),
            med(3, "Napa", "Paracetamol", "120 mg/5 ml", MedicineForm.SYRUP),
            med(4, "Ace", "Paracetamol", "500 mg", MedicineForm.TABLET),
            med(5, "Seclo", "Omeprazole", "20 mg", MedicineForm.CAPSULE),
            med(6, "Sergel", "Esomeprazole", "20 mg", MedicineForm.CAPSULE),
            med(7, "Losectil", "Omeprazole", "20 mg", MedicineForm.CAPSULE),
            med(16, "Cef-3", "Cefixime", "200 mg", MedicineForm.CAPSULE),
            med(25, "Ventolin", "Salbutamol", "100 mcg/puff", MedicineForm.INHALER));

    @Test
    public void normalizeLowersTrimsAndRemovesDashesAndExtraSpaces() {
        assertEquals("mirpur 10", SearchEngine.normalize("  Mirpur-10 "));
        assertEquals("seclo 20 mg capsule omeprazole", SearchEngine.normalize("Seclo 20 mg Capsule – Omeprazole"));
        assertEquals("cef 3", SearchEngine.normalize("CEF-3"));
    }

    @Test
    public void levenshtein() {
        assertEquals(0, SearchEngine.levenshtein("seclo", "seclo"));
        assertEquals(1, SearchEngine.levenshtein("seclow", "seclo"));
        assertEquals(1, SearchEngine.levenshtein("paracitamol", "paracetamol"));
        assertEquals(3, SearchEngine.levenshtein("kitten", "sitting"));
    }

    @Test
    public void exactBrandComesFirstAndIsNotFuzzy() {
        SearchEngine.MatchResult r = SearchEngine.match("Seclo", CATALOG);
        assertEquals("Seclo", r.getMedicines().get(0).getBrandName());
        assertEquals(SearchEngine.RANK_EXACT, r.getBestRank());
        assertFalse(r.isFuzzy());
        assertNull(r.getDidYouMean());
    }

    @Test
    public void genericNameFindsEveryBrand() {
        SearchEngine.MatchResult r = SearchEngine.match("omeprazole", CATALOG);
        List<String> brands = brands(r.getBestMedicines());
        assertEquals(Arrays.asList("Losectil", "Seclo"), brands);
    }

    @Test
    public void startsWithAndWordPrefixes() {
        assertEquals("Napa", SearchEngine.match("nap", CATALOG).getMedicines().get(0).getBrandName());
        SearchEngine.MatchResult r = SearchEngine.match("napa 500", CATALOG);
        assertEquals(1, r.getMedicines().get(0).getId());
        assertEquals("Cef-3", SearchEngine.match("cef 3", CATALOG).getMedicines().get(0).getBrandName());
    }

    @Test
    public void misspelledBrandSecloW() {
        SearchEngine.MatchResult r = SearchEngine.match("seclow", CATALOG);
        assertFalse(r.isEmpty());
        assertEquals("Seclo", r.getMedicines().get(0).getBrandName());
        assertTrue(r.isFuzzy());
        assertEquals("Seclo", r.getDidYouMean());
    }

    @Test
    public void misspelledGenericParacitamol() {
        SearchEngine.MatchResult r = SearchEngine.match("paracitamol", CATALOG);
        assertTrue(r.isFuzzy());
        assertEquals("Paracetamol", r.getDidYouMean());
        assertEquals(4, r.getMedicines().size());
        for (Medicine m : r.getMedicines()) {
            assertTrue(m.getBrandName(), m.getGenericName().startsWith("Paracetamol"));
        }
    }

    @Test
    public void shortWordsAreNotFuzzyAndNonsenseMatchesNothing() {
        assertTrue(SearchEngine.match("sexlo", CATALOG).isFuzzy());       // 5 letters: tolerated
        assertTrue(SearchEngine.match("acx", CATALOG).isEmpty());         // 3 letters: no spelling tolerance
        assertTrue(SearchEngine.match("xyzzyq", CATALOG).isEmpty());
        assertTrue(SearchEngine.match("   ", CATALOG).isEmpty());
    }

    @Test
    public void withinRadiusAddsDistanceAndFilters() {
        Area dhanmondi = new Area();
        dhanmondi.setId(1);
        dhanmondi.setLatitude(23.7461);
        dhanmondi.setLongitude(90.3742);
        PharmacyStock near = stock(1, 23.7470, 90.3750, 10, 2.0);      // ~0.1 km
        PharmacyStock gulshan = stock(2, 23.7806, 90.4160, 50, 1.5);   // ~5.7 km
        PharmacyStock uttara = stock(3, 23.8728, 90.3984, 5, 1.0);     // ~14 km
        List<PharmacyStock> in5 = SearchEngine.withinRadius(new ArrayList<PharmacyStock>(
                Arrays.asList(uttara, gulshan, near)), dhanmondi, 5.0);
        assertEquals(1, in5.size());
        assertEquals(1, in5.get(0).getPharmacyId());
        List<PharmacyStock> any = SearchEngine.withinRadius(new ArrayList<PharmacyStock>(
                Arrays.asList(uttara, gulshan, near)), dhanmondi, null);
        assertEquals(Arrays.asList(1, 2, 3), ids(any));                  // nearest first
        SearchEngine.sort(any, SearchEngine.Sort.PRICE);
        assertEquals(Arrays.asList(3, 2, 1), ids(any));
        SearchEngine.sort(any, SearchEngine.Sort.STOCK);
        assertEquals(Arrays.asList(2, 1, 3), ids(any));
    }

    @Test
    public void trustBreaksTiesAtTheSameShownDistance() {
        PharmacyStock lowTrust = stock(1, 0, 0, 10, 1.0);
        lowTrust.setDistanceKm(2.61);
        lowTrust.setTrustScore(70);
        PharmacyStock highTrust = stock(2, 0, 0, 10, 1.0);
        highTrust.setDistanceKm(2.64);          // also shown as "2.6 km"
        highTrust.setTrustScore(90);
        PharmacyStock nearer = stock(3, 0, 0, 10, 1.0);
        nearer.setDistanceKm(2.5);
        nearer.setTrustScore(10);
        List<PharmacyStock> rows = new ArrayList<PharmacyStock>(Arrays.asList(lowTrust, highTrust, nearer));
        SearchEngine.sort(rows, SearchEngine.Sort.DISTANCE);
        assertEquals("nearer always first, then trust decides the tie", Arrays.asList(3, 2, 1), ids(rows));
    }

    @Test
    public void staleAfter24Hours() {
        PharmacyStock fresh = stock(1, 0, 0, 1, 1);
        fresh.setLastUpdated(DateUtil.now());
        PharmacyStock old = stock(2, 0, 0, 1, 1);
        old.setLastUpdated("2020-01-01 10:00:00");
        assertFalse(SearchEngine.isStale(fresh));
        assertTrue(SearchEngine.isStale(old));
    }

    // ---------------------------------------------------------------- helpers

    private static Medicine med(int id, String brand, String generic, String strength, MedicineForm form) {
        Medicine m = new Medicine();
        m.setId(id);
        m.setBrandName(brand);
        m.setGenericName(generic);
        m.setStrength(strength);
        m.setForm(form);
        return m;
    }

    private static PharmacyStock stock(int id, double lat, double lon, int available, double price) {
        PharmacyStock s = new PharmacyStock();
        s.setPharmacyId(id);
        s.setLatitude(lat);
        s.setLongitude(lon);
        s.setAvailable(available);
        s.setLowestPrice(price);
        return s;
    }

    private static List<String> brands(List<Medicine> list) {
        List<String> out = new ArrayList<String>();
        for (Medicine m : list) {
            out.add(m.getBrandName());
        }
        return out;
    }

    private static List<Integer> ids(List<PharmacyStock> list) {
        List<Integer> out = new ArrayList<Integer>();
        for (PharmacyStock s : list) {
            out.add(s.getPharmacyId());
        }
        return out;
    }
}
