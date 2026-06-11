package iped.rcp.views;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItemId;
import iped.engine.data.IPEDMultiSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
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
import iped.rcp.api.ItemId;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.session.ICaseSessionManager;
import iped.utils.LocalizedFormat;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Auxiliary tables part (task T019, FR-007): subitems, parent item,
 * duplicates and reference tables synchronized with the current selection.
 * The Lucene queries are exact ports of the legacy table models
 * ({@code SubitemTableModel}, {@code ParentTableModel},
 * {@code DuplicatesTableModel}, {@code ReferencingTableModel},
 * {@code ReferencedByTableModel}); searches run in a Job off the UI thread.
 *
 * <p>
 * Display is capped at {@value #MAX_ROWS} rows per table in this increment
 * (tab titles always show the real totals) — recorded in the parity
 * inventory.
 */
public class AuxTablesPart {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuxTablesPart.class);

    private static final int MAX_ROWS = 1000;

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private UISynchronize uiSync;

    @Inject
    private MPart part;

    private CTabFolder folder;
    private final List<AuxTab> tabs = new ArrayList<>();
    private final AtomicLong refreshStamp = new AtomicLong();

    @PostConstruct
    public void createComposite(Composite parent) {
        parent.setLayout(new FillLayout());
        folder = new CTabFolder(parent, SWT.BORDER | SWT.BOTTOM);

        tabs.add(new AuxTab("SubitemTableModel.Subitens", this::subitemsQuery));
        tabs.add(new AuxTab("ParentTableModel.ParentCount", this::parentQuery));
        tabs.add(new AuxTab("DuplicatesTableModel.Duplicates", this::duplicatesQuery));
        tabs.add(new AuxTab("ReferencesTab.Title", this::referencingQuery));
        tabs.add(new AuxTab("ReferencedByTab.Title", this::referencedByQuery));

        for (AuxTab tab : tabs) {
            tab.create(folder);
        }
        folder.setSelection(0);
    }

    /** Selection sync: refresh all aux tables for the new active item. */
    @Inject
    @Optional
    public void onSelectionChanged(
            @Named(UiEventTopics.SELECTION_KEY) @Optional SelectionContext selection) {
        if (folder == null || folder.isDisposed()) {
            return;
        }
        if (selection != null && part.getElementId().equals(selection.originPartId())) {
            return; // no echo
        }
        ItemId active = selection == null ? null : selection.activeItem();
        long stamp = refreshStamp.incrementAndGet();
        if (active == null) {
            for (AuxTab tab : tabs) {
                tab.show(stamp, "", List.of(), 0);
            }
            return;
        }
        Job job = Job.create("aux-tables-refresh", (IProgressMonitor monitor) -> {
            try {
                refresh(stamp, active);
                return Status.OK_STATUS;
            } catch (Exception e) {
                LOGGER.warn("Aux tables refresh failed", e);
                return Status.OK_STATUS; // aux views degrade silently
            }
        });
        job.setSystem(true);
        job.schedule();
    }

    private void refresh(long stamp, ItemId active) throws Exception {
        IPEDMultiSource source = source();
        int luceneId = source.getLuceneId(new iped.engine.data.ItemId(active.sourceId(), active.id()));
        Document doc = source.getReader().document(luceneId);
        for (AuxTab tab : tabs) {
            if (stamp != refreshStamp.get()) {
                return; // a newer selection superseded this refresh
            }
            Query query = tab.queryFactory.create(doc);
            if (query == null) {
                tab.show(stamp, "", List.of(), 0);
                continue;
            }
            IPEDSearcher searcher = new IPEDSearcher(source, query);
            MultiSearchResult result = searcher.multiSearch();
            List<String[]> rows = new ArrayList<>(Math.min(result.getLength(), MAX_ROWS));
            Set<String> fieldsToLoad = new HashSet<>(Set.of(BasicProps.NAME, BasicProps.PATH));
            for (int i = 0; i < result.getLength() && i < MAX_ROWS; i++) {
                IItemId itemId = result.getItem(i);
                Document rowDoc = source.getReader().document(source.getLuceneId(itemId), fieldsToLoad);
                rows.add(new String[] { LocalizedFormat.format(i + 1), rowDoc.get(BasicProps.NAME),
                        rowDoc.get(BasicProps.PATH) });
            }
            tab.show(stamp, LocalizedFormat.format(result.getLength()), rows, result.getLength());
        }
    }

    // ------------------------------------------------------------------
    // Query ports of the legacy aux table models
    // ------------------------------------------------------------------

    private Query subitemsQuery(Document doc) {
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

    private Query parentQuery(Document doc) {
        String parentId = doc.get(BasicProps.PARENTID);
        if (parentId == null) {
            return null;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(IntPoint.newExactQuery(BasicProps.ID, Integer.parseInt(parentId)), Occur.MUST);
        builder.add(new TermQuery(new Term(BasicProps.EVIDENCE_UUID, doc.get(BasicProps.EVIDENCE_UUID))), Occur.MUST);
        return builder.build();
    }

    private Query duplicatesQuery(Document doc) {
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
    private Query referencingQuery(Document doc) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        String[] linkedItems = doc.getValues(ExtraProperties.LINKED_ITEMS);
        if (linkedItems.length > 0) {
            QueryBuilder b = new QueryBuilder(source());
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
    private Query referencedByQuery(Document doc) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        QueryBuilder b = new QueryBuilder(source());

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

    private IPEDMultiSource source() {
        return sessionManager.getSession().getSource();
    }

    /** One tab: localized title + query factory + result table. */
    private final class AuxTab {

        private final String titleKey;
        private final QueryFactory queryFactory;
        private CTabItem tabItem;
        private Table table;

        AuxTab(String titleKey, QueryFactory queryFactory) {
            this.titleKey = titleKey;
            this.queryFactory = queryFactory;
        }

        void create(CTabFolder parent) {
            tabItem = new CTabItem(parent, SWT.NONE);
            tabItem.setText(title(""));
            table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION);
            table.setHeaderVisible(true);
            TableColumn seq = new TableColumn(table, SWT.RIGHT);
            seq.setWidth(60);
            TableColumn name = new TableColumn(table, SWT.LEFT);
            name.setText(ResultColumns.labelOf(BasicProps.NAME));
            name.setWidth(250);
            TableColumn path = new TableColumn(table, SWT.LEFT);
            path.setText(ResultColumns.labelOf(BasicProps.PATH));
            path.setWidth(500);
            tabItem.setControl(table);
        }

        void show(long stamp, String countLabel, List<String[]> rows, int total) {
            uiSync.asyncExec(() -> {
                if (table.isDisposed() || stamp != refreshStamp.get()) {
                    return;
                }
                table.setRedraw(false);
                table.removeAll();
                for (String[] row : rows) {
                    TableItem item = new TableItem(table, SWT.NONE);
                    item.setText(row[0] == null ? "" : row[0]);
                    item.setText(1, row[1] == null ? "" : row[1]);
                    item.setText(2, row[2] == null ? "" : row[2]);
                }
                table.setRedraw(true);
                tabItem.setText(title(total > 0 ? countLabel : ""));
            });
        }

        private String title(String count) {
            String base = Messages.getString(titleKey).trim();
            return count.isEmpty() ? base : count + " " + base;
        }
    }

    @FunctionalInterface
    private interface QueryFactory {
        Query create(Document doc) throws Exception;
    }
}
