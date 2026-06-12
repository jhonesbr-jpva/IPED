package iped.rcp.core.filters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.data.IItemId;
import iped.engine.data.IPEDMultiSource;
import iped.engine.lucene.DocValuesUtil;
import iped.engine.search.ImageSimilarityLowScoreFilter;
import iped.engine.search.ImageSimilarityScorer;
import iped.engine.search.MultiSearchResult;
import iped.engine.search.SimilarDocumentSearch;
import iped.engine.search.SimilarFacesSearch;
import iped.engine.search.SimilarImagesSearch;
import iped.engine.task.index.IndexItem;
import iped.search.IMultiSearchResult;
import iped.viewers.api.IQueryFilter;
import iped.viewers.api.IResultSetFilter;

/**
 * Engine-backed similarity and duplicate filters (task T032, FR-013, legacy
 * {@code App.SimilarImageFilter}/{@code SimilarFacesSearchFilter}/
 * {@code SimilarDocumentFilter}/{@code DuplicateFilter}): thin factories over
 * the exact engine search classes the current UI uses, shaped as
 * {@code IQueryFilter}/{@code IResultSetFilter} for the
 * {@link FilterStateService} slots.
 */
public final class SimilarityFilters {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimilarityFilters.class);

    private SimilarityFilters() {
    }

    /**
     * Candidate query for similar images (legacy {@code SimilarImageFilter}
     * query leg). {@code getQuery()} is null when the reference item has no
     * similarity features (task disabled when the case was processed).
     */
    public static IQueryFilter similarImagesQuery(IItem refItem) {
        Query query = new SimilarImagesSearch().getQueryForSimilarImages(refItem);
        return new IQueryFilter() {
            @Override
            public Query getQuery() {
                return query;
            }

            @Override
            public String toString() {
                return "similarImages:" + refItem.getName();
            }
        };
    }

    /**
     * Score-and-cut leg of the similar images search (legacy
     * {@code SimilarImageFilter.filterResult}): engine scorer over the
     * candidates, then the low-score cut.
     */
    public static IResultSetFilter similarImagesRescore(IPEDMultiSource source, IItem refItem) {
        return new IResultSetFilter() {
            @Override
            public IMultiSearchResult filterResult(IMultiSearchResult src) throws IOException {
                long start = System.currentTimeMillis();
                MultiSearchResult result = (MultiSearchResult) src;
                new ImageSimilarityScorer(source, result, refItem).score();
                MultiSearchResult filtered = ImageSimilarityLowScoreFilter.filter(result);
                LOGGER.info("Similar image search took {}ms to find {} images",
                        System.currentTimeMillis() - start, filtered.getLength());
                return filtered;
            }

            @Override
            public String toString() {
                return "similarImages:" + refItem.getName();
            }
        };
    }

    /** Similar faces filter (legacy {@code SimilarFacesSearchFilter}). */
    public static IResultSetFilter similarFaces(IPEDMultiSource source, IItem refItem) {
        SimilarFacesSearch search = new SimilarFacesSearch(source, refItem);
        return new IResultSetFilter() {
            @Override
            public IMultiSearchResult filterResult(IMultiSearchResult src) throws IOException {
                return search.filter((MultiSearchResult) src);
            }

            @Override
            public String toString() {
                return "similarFaces:" + refItem.getName();
            }
        };
    }

    /**
     * Similar document query (legacy {@code SimilarDocumentFilter}); needs
     * stored term vectors — null query when unavailable.
     */
    public static IQueryFilter similarDocument(IPEDMultiSource source, IItemId refId, int matchPercent) {
        Query query = new SimilarDocumentSearch().getQueryForSimilarDocs(refId, matchPercent, source);
        return new IQueryFilter() {
            @Override
            public Query getQuery() {
                return query;
            }

            @Override
            public String toString() {
                return "similarDocument(" + matchPercent + "%)";
            }
        };
    }

    /**
     * Dynamic duplicates filter — port of the legacy
     * {@code iped.app.ui.DynamicDuplicateFilter} (lives in the Swing UI
     * module, so it cannot be reused): keeps the first occurrence of each
     * hash, in result order, letting hashless items through.
     */
    public static IResultSetFilter duplicates(IPEDMultiSource source) {
        return new IResultSetFilter() {
            @Override
            public IMultiSearchResult filterResult(IMultiSearchResult src) throws IOException {
                BitSet ordSet = new BitSet(1 << 23);
                LeafReader reader = source.getLeafReader();
                SortedDocValues docValues = reader.getSortedDocValues(IndexItem.HASH);

                List<IItemId> filteredItems = new ArrayList<>();
                List<Float> scores = new ArrayList<>();
                boolean filterOrdZero = false;
                try {
                    if (docValues != null && !docValues.lookupOrd(0).utf8ToString().isEmpty()) {
                        filterOrdZero = true;
                    }
                } catch (IOException e) {
                    LOGGER.warn("Error checking hash ord 0", e);
                }
                int i = 0;
                for (IItemId item : src.getIterator()) {
                    int docId = source.getLuceneId(item);
                    int ord = docValues == null ? -1 : DocValuesUtil.getOrd(docValues, docId);
                    if (ord < 0 || !ordSet.get(ord)) {
                        filteredItems.add(item);
                        scores.add(src.getScore(i));
                        if (ord > 0 || (ord == 0 && filterOrdZero)) {
                            ordSet.set(ord);
                        }
                    }
                    i++;
                }
                float[] primitive = new float[scores.size()];
                for (int k = 0; k < primitive.length; k++) {
                    primitive[k] = scores.get(k);
                }
                return new MultiSearchResult(filteredItems.toArray(new IItemId[0]), primitive);
            }

            @Override
            public String toString() {
                return "duplicates";
            }
        };
    }

}
