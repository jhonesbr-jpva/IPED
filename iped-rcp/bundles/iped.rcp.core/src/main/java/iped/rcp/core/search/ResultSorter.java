package iped.rcp.core.search;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItemId;
import iped.engine.data.IPEDMultiSource;
import iped.engine.localization.CategoryLocalization;
import iped.engine.search.MultiSearchResult;
import iped.engine.task.index.IndexItem;
import iped.properties.BasicProps;
import iped.properties.ExtraProperties;

/**
 * Engine-side sorting of a {@link MultiSearchResult} by an indexed field
 * (FR-007, research R12): functional port of the legacy
 * {@code iped.app.ui.RowComparator} DocValues strategy, decoupled from the
 * Swing table (no {@code App} singleton, no UI thread). Callers run it off
 * the UI thread (Jobs API in parts; plain thread in the parity harness).
 *
 * <p>
 * Strategy, in the legacy order: numeric doc values for non-String metadata
 * (single then multi-valued), sorted-set ordinals (with localized collation
 * for {@link BasicProps#CATEGORY}), sorted ordinals, and finally stored-field
 * values for fields without doc values. Descending order is the exact
 * reverse of ascending (deterministic for the parity harness; the legacy
 * toggle-based sorter could differ on ties — divergence recorded in the
 * parity inventory).
 *
 * <p>
 * Special (non-index) sort keys of the results table: {@link #FIELD_SCORE}
 * and {@link #FIELD_BOOKMARK} mirror the legacy score/bookmark columns.
 */
public final class ResultSorter {

    /** Sort by search score (legacy score column). */
    public static final String FIELD_SCORE = "$score";

    /** Sort by concatenated bookmark names (legacy bookmark column). */
    public static final String FIELD_BOOKMARK = "$bookmark";

    /** Sort by the checked state (legacy checkbox column). */
    public static final String FIELD_CHECKED = "$checked";

    private static final Logger LOGGER = LoggerFactory.getLogger(ResultSorter.class);

    private ResultSorter() {
    }

    /**
     * Returns a NEW result with the same items ordered by the given field.
     * The input result is not modified (atomic swap discipline of
     * {@link ResultSet}).
     */
    public static MultiSearchResult sort(IPEDMultiSource source, MultiSearchResult input, String field,
            boolean ascending) throws IOException {
        long start = System.currentTimeMillis();
        int length = input.getLength();
        Integer[] rows = new Integer[length];
        for (int i = 0; i < length; i++) {
            rows[i] = i;
        }

        Comparator<Integer> comparator = comparatorFor(source, input, field);
        Arrays.parallelSort(rows, comparator);
        if (!ascending) {
            Collections.reverse(Arrays.asList(rows));
        }

        IItemId[] ids = new IItemId[length];
        float[] scores = new float[length];
        for (int i = 0; i < length; i++) {
            ids[i] = input.getItem(rows[i]);
            scores[i] = input.getScore(rows[i]);
        }
        MultiSearchResult sorted = new MultiSearchResult(ids, scores);
        LOGGER.info("Sorted {} items by {} in {}ms", length, field, System.currentTimeMillis() - start);
        return sorted;
    }

    private static Comparator<Integer> comparatorFor(IPEDMultiSource source, MultiSearchResult input, String field)
            throws IOException {
        if (FIELD_SCORE.equals(field)) {
            return (a, b) -> Float.compare(input.getScore(a), input.getScore(b));
        }
        if (FIELD_BOOKMARK.equals(field)) {
            return (a, b) -> bookmarkNames(source, input.getItem(a)).compareTo(bookmarkNames(source, input.getItem(b)));
        }
        if (FIELD_CHECKED.equals(field)) {
            return (a, b) -> Boolean.compare(source.getMultiBookmarks().isChecked(input.getItem(b)),
                    source.getMultiBookmarks().isChecked(input.getItem(a)));
        }
        return new DocValuesComparator(source, input, field);
    }

    private static String bookmarkNames(IPEDMultiSource source, IItemId item) {
        return String.join(" | ", source.getMultiBookmarks().getBookmarkList(item));
    }

    /** DocValues ordinal comparator (legacy {@code RowComparator} port). */
    private static final class DocValuesComparator implements Comparator<Integer> {

        private final IPEDMultiSource source;
        private final MultiSearchResult input;
        private final String field;
        private final boolean isIntegerNumber;
        private final boolean isRealNumber;

        private int[] sdvOrds;
        private long[] ndvOrds;
        private int[][] ssdvOrds;
        private long[][] sndvOrds;
        private int[] localizedCategoryOrds;

        DocValuesComparator(IPEDMultiSource source, MultiSearchResult input, String field) throws IOException {
            this.source = source;
            this.input = input;
            this.field = field;
            this.isIntegerNumber = IndexItem.isIntegerNumber(field);
            this.isRealNumber = IndexItem.isRealNumber(field);
            loadDocValues();
        }

        private void loadDocValues() throws IOException {
            LeafReader reader = source.getLeafReader();
            SortedDocValues sdv = null;
            SortedSetDocValues ssdv = null;
            NumericDocValues ndv = null;
            SortedNumericDocValues sndv = null;

            Class<?> metadataType = IndexItem.getMetadataTypes().get(field);
            if (metadataType == null || !metadataType.equals(String.class)) {
                ndv = reader.getNumericDocValues(field);
                if (ndv == null) {
                    sndv = reader.getSortedNumericDocValues(field);
                }
            }
            if (ndv == null && sndv == null) {
                String prefix = ExtraProperties.LOCATIONS.equals(field) ? IndexItem.GEO_SSDV_PREFIX : "";
                ssdv = reader.getSortedSetDocValues(prefix + field);
                if (ssdv != null && BasicProps.CATEGORY.equals(field)) {
                    localizedCategoryOrds = localizedCategoryOrds(ssdv);
                }
            }
            if (ndv == null && sndv == null && ssdv == null) {
                sdv = reader.getSortedDocValues(field);
            }

            int maxDoc = reader.maxDoc();
            if (sdv != null) {
                sdvOrds = new int[maxDoc];
                for (int i = 0; i < maxDoc; i++) {
                    sdvOrds[i] = sdv.advanceExact(i) ? sdv.ordValue() : -1;
                }
            } else if (ndv != null) {
                ndvOrds = new long[maxDoc];
                for (int i = 0; i < maxDoc; i++) {
                    ndvOrds[i] = ndv.advanceExact(i) ? ndv.longValue() : Long.MIN_VALUE;
                }
            } else if (ssdv != null) {
                int[] empty = new int[0];
                ssdvOrds = new int[maxDoc][];
                for (int i = 0; i < maxDoc; i++) {
                    if (ssdv.advanceExact(i)) {
                        ArrayList<Integer> ords = new ArrayList<>();
                        int ord;
                        while ((ord = (int) ssdv.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
                            ords.add(ord);
                        }
                        ssdvOrds[i] = ords.stream().mapToInt(Integer::intValue).toArray();
                    } else {
                        ssdvOrds[i] = empty;
                    }
                }
            } else if (sndv != null) {
                long[] empty = new long[0];
                sndvOrds = new long[maxDoc][];
                for (int i = 0; i < maxDoc; i++) {
                    if (sndv.advanceExact(i)) {
                        sndvOrds[i] = new long[sndv.docValueCount()];
                        for (int j = 0; j < sndvOrds[i].length; j++) {
                            sndvOrds[i][j] = sndv.nextValue();
                        }
                    } else {
                        sndvOrds[i] = empty;
                    }
                }
            }
        }

        private static int[] localizedCategoryOrds(SortedSetDocValues ssdv) throws IOException {
            int[] localizedOrds = new int[(int) ssdv.getValueCount()];
            ArrayList<String> localizedVals = new ArrayList<>();
            for (int i = 0; i < localizedOrds.length; i++) {
                localizedVals.add(CategoryLocalization.getInstance()
                        .getLocalizedCategory(ssdv.lookupOrd(i).utf8ToString()));
            }
            ArrayList<String> sorted = new ArrayList<>(localizedVals);
            Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            sorted.sort(collator);
            for (int i = 0; i < localizedOrds.length; i++) {
                localizedOrds[i] = sorted.indexOf(localizedVals.get(i));
            }
            return localizedOrds;
        }

        @Override
        public int compare(Integer rowA, Integer rowB) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("Sort canceled");
            }
            int a = source.getLuceneId(input.getItem(rowA));
            int b = source.getLuceneId(input.getItem(rowB));

            if (sdvOrds != null) {
                return Integer.compare(sdvOrds[a], sdvOrds[b]);
            }
            if (ndvOrds != null) {
                return Long.compare(ndvOrds[a], ndvOrds[b]);
            }
            if (ssdvOrds != null) {
                int result;
                int k = 0;
                int ordA;
                int ordB;
                do {
                    ordA = k < ssdvOrds[a].length ? ssdvOrds[a][k] : -1;
                    ordB = k < ssdvOrds[b].length ? ssdvOrds[b][k] : -1;
                    if (localizedCategoryOrds != null) {
                        if (ordA > -1) {
                            ordA = localizedCategoryOrds[ordA];
                        }
                        if (ordB > -1) {
                            ordB = localizedCategoryOrds[ordB];
                        }
                    }
                    result = ordA - ordB;
                    k++;
                } while (result == 0 && ordA != -1 && ordB != -1);
                return result;
            }
            if (sndvOrds != null) {
                int result;
                int k = 0;
                int countA = sndvOrds[a].length;
                int countB = sndvOrds[b].length;
                do {
                    long ordA = k < countA ? sndvOrds[a][k] : Long.MIN_VALUE;
                    long ordB = k < countB ? sndvOrds[b][k] : Long.MIN_VALUE;
                    result = Long.compare(ordA, ordB);
                    k++;
                } while (result == 0 && (k < countA || k < countB));
                return result;
            }
            return compareStoredValues(a, b);
        }

        /** Legacy on-demand fallback for fields without doc values (slower). */
        private int compareStoredValues(int docA, int docB) {
            try {
                Set<String> fieldsToLoad = new HashSet<>(Set.of(field));
                Document doc1 = source.getReader().document(docA, fieldsToLoad);
                Document doc2 = source.getReader().document(docB, fieldsToLoad);
                String v1 = doc1.get(field);
                String v2 = doc2.get(field);
                if (v1 == null || v1.isEmpty()) {
                    return (v2 == null || v2.isEmpty()) ? 0 : -1;
                }
                if (v2 == null || v2.isEmpty()) {
                    return 1;
                }
                if (isIntegerNumber) {
                    return Long.compare(Long.parseLong(v1), Long.parseLong(v2));
                }
                if (isRealNumber) {
                    return Double.compare(Double.parseDouble(v1), Double.parseDouble(v2));
                }
                return v1.compareTo(v2);
            } catch (IOException | NumberFormatException e) {
                LOGGER.warn("Error comparing stored values of field {}", field, e);
                return 0;
            }
        }
    }
}
