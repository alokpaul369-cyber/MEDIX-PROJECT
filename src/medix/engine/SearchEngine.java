package medix.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import medix.model.Area;
import medix.model.Medicine;
import medix.model.PharmacyStock;
import medix.util.DateUtil;

/**
 * Medicine name matching and pharmacy ranking. Pure logic: no database access,
 * so it can be unit tested with plain lists.
 *
 * Matching ranks (lower is better), on normalised text:
 * <ol start="0">
 *   <li>exact: the brand, the generic name or the full label equals the query</li>
 *   <li>starts with the query</li>
 *   <li>contains the query</li>
 *   <li>every query word is the start of a word of the label (e.g. "napa 500")</li>
 *   <li>fuzzy: a query word of 5+ letters is within Levenshtein distance 2 of a name word
 *       (e.g. "seclow" → Seclo, "paracitamol" → Paracetamol)</li>
 * </ol>
 */
public final class SearchEngine {

    /** Stock not updated for this many hours is "stale" (shown with ⚠). */
    public static final int STALE_HOURS = 24;
    public static final int MIN_FUZZY_WORD_LENGTH = 5;
    public static final int MAX_EDIT_DISTANCE = 2;

    public static final int RANK_EXACT = 0;
    public static final int RANK_STARTS_WITH = 1;
    public static final int RANK_CONTAINS = 2;
    public static final int RANK_WORDS = 3;
    public static final int RANK_FUZZY = 4;
    private static final int NO_MATCH = Integer.MAX_VALUE;

    /** Result order for the pharmacy table. */
    public enum Sort {
        DISTANCE("Distance"), PRICE("Price"), STOCK("Most stock");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Matching medicines, best first. */
    public static final class MatchResult {
        private final List<Medicine> medicines;
        private final int bestRank;
        private final String didYouMean;

        MatchResult(List<Medicine> medicines, int bestRank, String didYouMean) {
            this.medicines = medicines;
            this.bestRank = bestRank;
            this.didYouMean = didYouMean;
        }

        public List<Medicine> getMedicines() {
            return medicines;
        }

        public boolean isEmpty() {
            return medicines.isEmpty();
        }

        /** True when the best match needed spelling tolerance. */
        public boolean isFuzzy() {
            return bestRank == RANK_FUZZY;
        }

        public int getBestRank() {
            return bestRank;
        }

        /** "Seclo" for "seclow"; null when the best match is not fuzzy. */
        public String getDidYouMean() {
            return didYouMean;
        }

        /** The medicines that share the best rank (e.g. every Paracetamol brand). */
        public List<Medicine> getBestMedicines() {
            List<Medicine> best = new ArrayList<Medicine>();
            for (int i = 0; i < medicines.size() && ranks.get(i) == bestRank; i++) {
                best.add(medicines.get(i));
            }
            return best;
        }

        private List<Integer> ranks = new ArrayList<Integer>();
    }

    private SearchEngine() {
    }

    // ---------------------------------------------------------------- text

    /** Lower case, trimmed, dashes (- – —) and repeated spaces turned into one space. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase().replaceAll("[\\-–—]+", " ").replaceAll("\\s+", " ").trim();
    }

    /** Classic edit distance (insert, delete, replace). */
    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ---------------------------------------------------------------- medicines

    /** Every catalog medicine that matches the query, best first (rank, then brand, strength). */
    public static MatchResult match(String query, List<Medicine> catalog) {
        final String q = normalize(query);
        List<Medicine> found = new ArrayList<Medicine>();
        final List<Integer> ranks = new ArrayList<Integer>();
        if (q.isEmpty()) {
            return new MatchResult(found, NO_MATCH, null);
        }
        List<Object[]> scored = new ArrayList<Object[]>();
        for (Medicine m : catalog) {
            int rank = rank(q, m);
            if (rank != NO_MATCH) {
                scored.add(new Object[]{m, rank});
            }
        }
        Collections.sort(scored, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] x, Object[] y) {
                int r = Integer.compare((Integer) x[1], (Integer) y[1]);
                if (r != 0) {
                    return r;
                }
                Medicine a = (Medicine) x[0];
                Medicine b = (Medicine) y[0];
                r = a.getBrandName().compareToIgnoreCase(b.getBrandName());
                return r != 0 ? r : a.getStrength().compareToIgnoreCase(b.getStrength());
            }
        });
        for (Object[] s : scored) {
            found.add((Medicine) s[0]);
            ranks.add((Integer) s[1]);
        }
        int best = ranks.isEmpty() ? NO_MATCH : ranks.get(0);
        String didYouMean = best == RANK_FUZZY ? fuzzyName(q, found.get(0)) : null;
        MatchResult result = new MatchResult(found, best, didYouMean);
        result.ranks = ranks;
        return result;
    }

    /** Rank of one medicine for the normalised query, or NO_MATCH. */
    static int rank(String q, Medicine m) {
        String brand = normalize(m.getBrandName());
        String generic = normalize(m.getGenericName());
        String product = normalize(m.getBrandName() + " " + m.getStrength() + " " + m.getForm());
        String label = normalize(product + " " + m.getGenericName());
        if (q.equals(brand) || q.equals(generic) || q.equals(product) || q.equals(label)) {
            return RANK_EXACT;
        }
        if (brand.startsWith(q) || generic.startsWith(q) || label.startsWith(q)) {
            return RANK_STARTS_WITH;
        }
        if (brand.contains(q) || generic.contains(q) || label.contains(q)) {
            return RANK_CONTAINS;
        }
        String[] words = label.split(" ");
        boolean fuzzy = false;
        for (String token : q.split(" ")) {
            if (startsSomeWord(token, words)) {
                continue;
            }
            if (token.length() >= MIN_FUZZY_WORD_LENGTH && closeToSomeWord(token, words)) {
                fuzzy = true;
                continue;
            }
            return NO_MATCH;
        }
        return fuzzy ? RANK_FUZZY : RANK_WORDS;
    }

    private static boolean startsSomeWord(String token, String[] words) {
        for (String w : words) {
            if (w.startsWith(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean closeToSomeWord(String token, String[] words) {
        return closestWord(token, words) != null;
    }

    /** The name word within MAX_EDIT_DISTANCE of the token (closest first), or null. */
    private static String closestWord(String token, String[] words) {
        String best = null;
        int bestDistance = MAX_EDIT_DISTANCE + 1;
        for (String w : words) {
            if (w.length() < 3 || Math.abs(w.length() - token.length()) > MAX_EDIT_DISTANCE) {
                continue;
            }
            int d = levenshtein(token, w);
            if (d < bestDistance) {
                bestDistance = d;
                best = w;
            }
        }
        return best;
    }

    /**
     * The catalog word the user probably meant, spelled as in the catalog: "Seclo" for "seclow",
     * "Paracetamol" for "paracitamol" (just the word, not "Paracetamol + Caffeine").
     */
    private static String fuzzyName(String q, Medicine m) {
        List<String> original = new ArrayList<String>();
        Collections.addAll(original, m.getBrandName().split("\\s+"));
        Collections.addAll(original, m.getGenericName().split("\\s+"));
        for (String token : q.split(" ")) {
            if (token.length() < MIN_FUZZY_WORD_LENGTH) {
                continue;
            }
            for (String word : original) {
                String w = normalize(word);
                if (w.length() >= 3 && Math.abs(w.length() - token.length()) <= MAX_EDIT_DISTANCE
                        && levenshtein(token, w) <= MAX_EDIT_DISTANCE) {
                    return word.replaceAll("[^\\p{L}\\p{N}\\-]", "");
                }
            }
        }
        return m.getBrandName();
    }

    // ---------------------------------------------------------------- pharmacies

    /**
     * Sets distanceKm (from the area centre to each pharmacy) and keeps the rows within
     * radiusKm (null = any distance). The rows are returned in distance order.
     */
    public static List<PharmacyStock> withinRadius(List<PharmacyStock> rows, Area from, Double radiusKm) {
        List<PharmacyStock> kept = new ArrayList<PharmacyStock>();
        for (PharmacyStock r : rows) {
            double km = GeoUtil.distanceKm(from.getLatitude(), from.getLongitude(), r.getLatitude(), r.getLongitude());
            r.setDistanceKm(km);
            if (radiusKm == null || km <= radiusKm) {
                kept.add(r);
            }
        }
        sort(kept, Sort.DISTANCE);
        return kept;
    }

    /**
     * Sorts in place. Distance: nearest first; pharmacies at the SAME shown distance (same 0.1 km)
     * are ordered by trust score (highest first), then price. Price: cheapest first, then distance,
     * then trust. Most stock: most available first, then distance, then trust.
     */
    public static void sort(List<PharmacyStock> rows, final Sort sort) {
        Collections.sort(rows, new Comparator<PharmacyStock>() {
            @Override
            public int compare(PharmacyStock a, PharmacyStock b) {
                int r;
                if (sort == Sort.PRICE) {
                    r = Double.compare(a.getLowestPrice(), b.getLowestPrice());
                } else if (sort == Sort.STOCK) {
                    r = Integer.compare(b.getAvailable(), a.getAvailable());
                } else {
                    r = Long.compare(shownDistance(a), shownDistance(b));
                }
                if (r == 0 && sort != Sort.DISTANCE) {
                    r = Long.compare(shownDistance(a), shownDistance(b));
                }
                if (r == 0) {
                    r = Integer.compare(b.getTrustScore(), a.getTrustScore());
                }
                if (r == 0) {
                    r = sort == Sort.PRICE ? Double.compare(a.getDistanceKm(), b.getDistanceKm())
                            : Double.compare(a.getLowestPrice(), b.getLowestPrice());
                }
                return r != 0 ? r : Double.compare(a.getDistanceKm(), b.getDistanceKm());
            }
        });
    }

    /** Distance in tenths of a km, as shown ("2.6 km"), so "the same distance" means the same text. */
    private static long shownDistance(PharmacyStock s) {
        return Math.round(s.getDistanceKm() * 10);
    }

    /** Stock not updated for STALE_HOURS or more. */
    public static boolean isStale(PharmacyStock row) {
        return DateUtil.isOlderThanHours(row.getLastUpdated(), STALE_HOURS);
    }
}
