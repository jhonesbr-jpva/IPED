package iped.rcp.core.trees;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItemId;
import iped.engine.data.IPEDMultiSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.engine.search.QueryBuilder;
import iped.engine.task.index.IndexItem;

/**
 * Headless evidence/filesystem tree model (task T027, FR-009/AR-01): port of
 * the legacy {@code TreeViewModel} queries — roots are {@code isRoot:true},
 * children are {@code parentId + evidenceUUID} restricted to folders or
 * items with children, sorted by name with a PRIMARY-strength collator. Also
 * builds the selection filter queries of the legacy {@code TreeListener}
 * (direct children listing vs recursive subtree).
 */
public class EvidenceTreeModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceTreeModel.class);

    private final IPEDMultiSource source;
    private final Collator collator;

    /** A filesystem node: global Lucene doc id + lazy stored document. */
    public class Node {

        public final int docId;
        private Document doc;
        private List<Node> children;

        Node(int docId) {
            this.docId = docId;
        }

        public Document getDoc() {
            if (doc == null) {
                try {
                    doc = source.getReader().document(docId);
                } catch (IOException e) {
                    LOGGER.warn("Error loading tree node doc {}", docId, e);
                    doc = new Document();
                }
            }
            return doc;
        }

        public String getName() {
            String name = getDoc().get(IndexItem.NAME);
            return name != null ? name : "";
        }

        /** Lazy children (legacy listSubItens query), computed once. */
        public List<Node> getChildren() {
            if (children == null) {
                Document document = getDoc();
                String parentId = document.get(IndexItem.ID);
                String sourceUUID = document.get(IndexItem.EVIDENCE_UUID);
                String textQuery = "(" + IndexItem.PARENTID + ":" + parentId + " && " + IndexItem.EVIDENCE_UUID + ":"
                        + sourceUUID + ") && (" + IndexItem.ISDIR + ":true || " + IndexItem.HASCHILD + ":true)";
                children = searchNodes(textQuery);
            }
            return children;
        }
    }

    public EvidenceTreeModel(IPEDMultiSource source) {
        this.source = source;
        this.collator = Collator.getInstance();
        this.collator.setStrength(Collator.PRIMARY);
    }

    /** Evidence roots ({@code isRoot:true}), name-sorted (legacy root query). */
    public List<Node> getRoots() {
        return searchNodes(IndexItem.ISROOT + ":true");
    }

    /**
     * Filter query for the selected nodes, non-recursive (legacy
     * {@code TreeListener.treeQuery}: direct children of each selection).
     */
    public Query directChildrenQuery(List<Node> selection) {
        StringBuilder textQuery = new StringBuilder();
        for (Node node : selection) {
            Document doc = node.getDoc();
            textQuery.append('(').append(IndexItem.PARENTID).append(':').append(doc.get(IndexItem.ID)).append(" && ")
                    .append(IndexItem.EVIDENCE_UUID).append(':').append(doc.get(IndexItem.EVIDENCE_UUID))
                    .append(") ");
        }
        try {
            return new QueryBuilder(source).getQuery(textQuery.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Error building tree filter query", e);
        }
    }

    /**
     * Filter query for the selected nodes, recursive (legacy
     * {@code TreeListener.recursiveTreeQuery}: whole subtree via parentIds).
     */
    public Query recursiveQuery(List<Node> selection) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (Node node : selection) {
            Document doc = node.getDoc();
            BooleanQuery.Builder subQuery = new BooleanQuery.Builder();
            subQuery.add(new TermQuery(new Term(IndexItem.PARENTIDs, doc.get(IndexItem.ID))), Occur.MUST);
            subQuery.add(new TermQuery(new Term(IndexItem.EVIDENCE_UUID, doc.get(IndexItem.EVIDENCE_UUID))),
                    Occur.MUST);
            builder.add(subQuery.build(), Occur.SHOULD);
        }
        return builder.build();
    }

    private List<Node> searchNodes(String textQuery) {
        try {
            IPEDSearcher searcher = new IPEDSearcher(source, textQuery);
            searcher.setTreeQuery(true);
            MultiSearchResult result = searcher.multiSearch();

            List<Integer> docIds = new ArrayList<>(result.getLength());
            for (IItemId item : result.getIterator()) {
                docIds.add(source.getLuceneId(item));
            }
            docIds.sort(nameComparator());

            List<Node> nodes = new ArrayList<>(docIds.size());
            for (int docId : docIds) {
                nodes.add(new Node(docId));
            }
            return nodes;
        } catch (Exception e) {
            LOGGER.error("Error listing evidence tree nodes", e);
            return List.of();
        }
    }

    private Comparator<Integer> nameComparator() {
        final Set<String> fields = new HashSet<>();
        fields.add(IndexItem.NAME);
        return (a, b) -> {
            try {
                Document doc1 = source.getReader().document(a, fields);
                Document doc2 = source.getReader().document(b, fields);
                return collator.compare(String.valueOf(doc1.get(IndexItem.NAME)),
                        String.valueOf(doc2.get(IndexItem.NAME)));
            } catch (IOException e) {
                return 0;
            }
        };
    }
}
