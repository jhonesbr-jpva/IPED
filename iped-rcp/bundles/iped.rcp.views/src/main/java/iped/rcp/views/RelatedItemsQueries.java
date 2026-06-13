package iped.rcp.views;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.data.IPEDMultiSource;
import iped.engine.search.QueryBuilder;
import iped.engine.task.HashTask;
import iped.engine.task.jumplist.JumpListTask;
import iped.exception.ParseException;
import iped.exception.QueryNodeException;
import iped.parsers.ares.AresParser;
import iped.parsers.emule.KnownMetParser;
import iped.parsers.shareaza.ShareazaLibraryDatParser;
import iped.properties.BasicProps;
import iped.properties.ExtraProperties;

/**
 * Lucene queries relating an item to its neighborhood — exact ports of the
 * legacy table models ({@code SubitemTableModel}, {@code ParentTableModel},
 * {@code DuplicatesTableModel}, {@code ReferencingTableModel},
 * {@code ReferencedByTableModel}). Extracted from {@link AuxTablesPart}
 * (T019) so the check-with-related keyboard shortcuts (T046, FR-021) reuse
 * the same queries the legacy {@code ResultTableListener} actions reuse from
 * the aux models.
 *
 * <p>
 * All factories return {@code null} when the document cannot anchor the
 * query (same contract as the legacy {@code createQuery}).
 */
public final class RelatedItemsQueries {

    private static final Logger LOGGER = LoggerFactory.getLogger(RelatedItemsQueries.class);

    private RelatedItemsQueries() {
    }

    /** Direct children of the item (legacy {@code SubitemTableModel}). */
    public static Query subitems(IPEDMultiSource source, Document doc) {
        String id = doc.get(BasicProps.ID);
        String sourceUUID = doc.get(BasicProps.EVIDENCE_UUID);
        if (id == null || sourceUUID == null) {
            return null;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(IntPoint.newExactQuery(BasicProps.PARENTID, Integer.parseInt(id)), Occur.MUST);
        builder.add(new TermQuery(new Term(BasicProps.EVIDENCE_UUID, sourceUUID)), Occur.MUST);
        return builder.build();
    }

    /** The parent item (legacy {@code ParentTableModel}). */
    public static Query parent(IPEDMultiSource source, Document doc) {
        String parentId = doc.get(BasicProps.PARENTID);
        if (parentId == null) {
            return null;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(IntPoint.newExactQuery(BasicProps.ID, Integer.parseInt(parentId)), Occur.MUST);
        builder.add(new TermQuery(new Term(BasicProps.EVIDENCE_UUID, doc.get(BasicProps.EVIDENCE_UUID))), Occur.MUST);
        return builder.build();
    }

    /** Other items with the same hash (legacy {@code DuplicatesTableModel}). */
    public static Query duplicates(IPEDMultiSource source, Document doc) {
        String hash = doc.get(BasicProps.HASH);
        if (hash == null || hash.isBlank()) {
            return null;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new TermQuery(new Term(BasicProps.HASH, hash.toLowerCase())), Occur.MUST);
        builder.add(IntPoint.newExactQuery(BasicProps.ID, Integer.parseInt(doc.get(BasicProps.ID))), Occur.MUST_NOT);
        return builder.build();
    }

    /** Items this document references (legacy {@code ReferencingTableModel}). */
    public static Query referencing(IPEDMultiSource source, Document doc) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        String[] linkedItems = doc.getValues(ExtraProperties.LINKED_ITEMS);
        if (linkedItems.length > 0) {
            QueryBuilder b = new QueryBuilder(source);
            for (String q : linkedItems) {
                try {
                    builder.add(b.getQuery(q), Occur.SHOULD);
                } catch (ParseException | QueryNodeException e) {
                    LOGGER.warn("Invalid linkedItems query: {}", q, e);
                }
            }
        }

        String[] sharedHashes = doc.getValues(ExtraProperties.SHARED_HASHES);
        if (sharedHashes.length > 0) {
            String field;
            String mediaType = doc.get(BasicProps.CONTENTTYPE);
            if (KnownMetParser.EMULE_MIME_TYPE.equals(mediaType)) {
                field = HashTask.HASH.EDONKEY.toString();
            } else if (AresParser.ARES_MIME_TYPE.equals(mediaType)) {
                field = HashTask.HASH.SHA1.toString();
            } else if (ShareazaLibraryDatParser.LIBRARY_DAT_MIME_TYPE.equals(mediaType)) {
                field = HashTask.HASH.MD5.toString();
            } else {
                field = BasicProps.HASH;
            }
            Set<BytesRef> hashes = Arrays.stream(sharedHashes).filter(h -> h != null && !h.isBlank())
                    .map(BytesRef::new).collect(Collectors.toSet());
            builder.add(new TermInSetQuery(field, hashes), Occur.SHOULD);
        }

        String[] jumpTargets = doc.getValues(ExtraProperties.UFED_JUMP_TARGETS);
        for (String jumpTarget : jumpTargets) {
            builder.add(new TermQuery(new Term(ExtraProperties.UFED_ID, jumpTarget)), Occur.SHOULD);
        }
        String fileId = doc.get(ExtraProperties.UFED_FILE_ID);
        if (fileId != null && !fileId.isBlank()) {
            builder.add(new TermQuery(new Term(ExtraProperties.UFED_ID, fileId)), Occur.SHOULD);
        }

        builder.add(IntPoint.newExactQuery(BasicProps.ID, Integer.parseInt(doc.get(BasicProps.ID))), Occur.MUST_NOT);
        BooleanQuery query = builder.build();
        return query.clauses().size() > 1 ? query : null;
    }

    /** Items referencing this document (legacy {@code ReferencedByTableModel}). */
    public static Query referencedBy(IPEDMultiSource source, Document doc) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        QueryBuilder b = new QueryBuilder(source);

        String md5 = doc.get(HashTask.HASH.MD5.toString());
        String sha1 = doc.get(HashTask.HASH.SHA1.toString());
        String sha256 = doc.get(HashTask.HASH.SHA256.toString());
        String edonkey = doc.get(HashTask.HASH.EDONKEY.toString());
        String hashes = Arrays.asList(md5, sha1, sha256, edonkey).stream().filter(h -> h != null && !h.isBlank())
                .collect(Collectors.joining(" "));
        if (!hashes.isEmpty()) {
            try {
                builder.add(b.getQuery(ExtraProperties.LINKED_ITEMS + ":(" + hashes + ") "), Occur.SHOULD);
                builder.add(b.getQuery(ExtraProperties.SHARED_HASHES + ":(" + hashes + ")"), Occur.SHOULD);
            } catch (ParseException | QueryNodeException e) {
                LOGGER.warn("Invalid hash reference query", e);
            }
        }

        String trackId = doc.get(BasicProps.TRACK_ID);
        if (trackId != null && !trackId.isBlank()) {
            String trackIdQuery = QueryBuilder.escape(BasicProps.TRACK_ID + ":" + trackId);
            try {
                builder.add(b.getQuery(ExtraProperties.LINKED_ITEMS + ":\"" + trackIdQuery + "\""), Occur.SHOULD);
            } catch (ParseException | QueryNodeException e) {
                LOGGER.warn("Invalid trackId reference query", e);
            }
        }

        String[] appIds = doc.getValues(JumpListTask.JUMPLIST_PROGRAM_APP_IDS);
        for (String appId : appIds) {
            String appIdQuery = QueryBuilder.escape(JumpListTask.JUMPLIST_PROGRAM_APP_IDS) + ":" + appId;
            try {
                builder.add(b.getQuery(ExtraProperties.LINKED_ITEMS + ":\"" + appIdQuery + "\""), Occur.SHOULD);
            } catch (ParseException | QueryNodeException e) {
                LOGGER.warn("Invalid appId reference query", e);
            }
        }

        String ufedId = doc.get(ExtraProperties.UFED_ID);
        if (ufedId != null && !ufedId.isBlank()) {
            builder.add(new TermQuery(new Term(ExtraProperties.UFED_JUMP_TARGETS, ufedId)), Occur.SHOULD);
            builder.add(new TermQuery(new Term(ExtraProperties.UFED_FILE_ID, ufedId)), Occur.SHOULD);
            try {
                builder.add(b.getQuery(ExtraProperties.LINKED_ITEMS + ":\"" + ufedId + "\""), Occur.SHOULD);
            } catch (ParseException | QueryNodeException e) {
                LOGGER.warn("Invalid ufedId reference query", e);
            }
        }

        builder.add(IntPoint.newExactQuery(BasicProps.ID, Integer.parseInt(doc.get(BasicProps.ID))), Occur.MUST_NOT);
        BooleanQuery query = builder.build();
        return query.clauses().size() > 1 ? query : null;
    }
}
