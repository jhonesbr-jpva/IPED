package iped.rcp.core.trees;

import java.io.IOException;
import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Document;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.config.AIFiltersConfig;
import iped.engine.config.ConfigurationManager;
import iped.engine.data.IPEDMultiSource;
import iped.engine.data.SimpleFilterNode;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.LuceneSearchResult;
import iped.engine.search.SimpleNodeFilterSearch;

/**
 * Headless AI filters tree (task T029, FR-009/AR-04): port of the legacy
 * {@code AIFiltersLoader}/{@code AIFiltersTreeListener} — clones the
 * configured {@code SimpleFilterNode} tree ({@code AIFiltersConfig.json}),
 * sorts/counts/expands dynamic nodes against the open case and builds the
 * SHOULD-combined selection query.
 */
public class AiFiltersModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiFiltersModel.class);

    private final IPEDMultiSource source;
    private final SimpleFilterNode root;

    public AiFiltersModel(IPEDMultiSource source) {
        this.source = source;
        this.root = load();
    }

    /** Null when the AI filters config is absent (feature not configured). */
    public SimpleFilterNode getRoot() {
        return root;
    }

    /** SHOULD-combined query of the selected nodes (legacy listener). */
    public Query selectionQuery(List<SimpleFilterNode> selection) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        boolean any = false;
        for (SimpleFilterNode node : selection) {
            Query query = SimpleNodeFilterSearch.getNodeQuery(source, node);
            if (query != null) {
                builder.add(query, Occur.SHOULD);
                any = true;
            }
        }
        return any ? builder.build() : null;
    }

    private SimpleFilterNode load() {
        AIFiltersConfig config = ConfigurationManager.get() != null
                ? ConfigurationManager.get().findObject(AIFiltersConfig.class)
                : null;
        if (config == null || config.getRootAIFilter() == null) {
            return null;
        }
        try {
            SimpleFilterNode rootNode = (SimpleFilterNode) config.getRootAIFilter().clone();
            sortChildrenNodes(rootNode, new SimpleFilterNode.LocalizedNameComparator());
            updateCount(rootNode);
            removeEmptyTopLevel(rootNode);
            expandDynamic(rootNode);
            return rootNode;
        } catch (RuntimeException e) {
            LOGGER.error("Error loading AI filters tree", e);
            return null;
        }
    }

    private void sortChildrenNodes(SimpleFilterNode node, Comparator<SimpleFilterNode> comparator) {
        if ("true".equalsIgnoreCase(node.getSortChildren())) {
            Collections.sort(node.getChildren(), comparator);
        }
        for (SimpleFilterNode child : node.getChildren()) {
            sortChildrenNodes(child, comparator);
        }
    }

    private void removeEmptyTopLevel(SimpleFilterNode rootNode) {
        for (int i = 0; i < rootNode.getChildren().size(); i++) {
            if (rootNode.getChildren().get(i).getNumItems() <= 0) {
                rootNode.getChildren().remove(i--);
            }
        }
    }

    private void updateCount(SimpleFilterNode node) {
        Query query = SimpleNodeFilterSearch.getNodeQuery(source, node);
        if (query != null) {
            IPEDSearcher searcher = new IPEDSearcher(source, query);
            searcher.setNoScoring(true);
            int num = 0;
            try {
                num = searcher.multiSearch().getLength();
            } catch (Exception e) {
                LOGGER.warn("Error counting AI filter node {}", node.getName(), e);
            }
            node.setNumItems(num);
        }
        for (SimpleFilterNode child : node.getChildren()) {
            updateCount(child);
        }
    }

    private void expandDynamic(SimpleFilterNode node) {
        if (node.getDynamic()) {
            try {
                // read distinct values and their frequency (legacy loader)
                Query query = SimpleNodeFilterSearch.getNodeQuery(source, node);
                IPEDSearcher ipedSearcher = new IPEDSearcher(source, query);
                Map<String, Integer> values = new HashMap<>();
                LuceneSearchResult result = ipedSearcher.luceneSearch();
                int[] ids = result.getLuceneIds();
                IndexSearcher indexSearcher = source.getSearcher();
                String property = node.getProperty().replaceAll("\\\\", "");
                for (int id : ids) {
                    Document doc = indexSearcher.doc(id);
                    for (String value : doc.getValues(property)) {
                        values.merge(value, 1, Integer::sum);
                    }
                }

                for (Map.Entry<String, Integer> entry : values.entrySet()) {
                    SimpleFilterNode child = new SimpleFilterNode();
                    child.setDynamicChild(true);
                    child.setName(entry.getKey().replace('_', ' '));
                    child.setValue(entry.getKey());
                    child.setNumItems(entry.getValue());
                    child.setParent(node);
                    node.getChildren().add(child);
                }

                Collator collator = Collator.getInstance();
                collator.setStrength(Collator.PRIMARY);
                Collections.sort(node.getChildren(), (a, b) -> {
                    int cmp = Integer.compare(b.getNumItems(), a.getNumItems());
                    return cmp == 0 ? collator.compare(a.getName(), b.getName()) : cmp;
                });
            } catch (IOException e) {
                LOGGER.error("Error expanding dynamic AI filter node {}", node.getName(), e);
            }
        } else {
            for (SimpleFilterNode child : node.getChildren()) {
                expandDynamic(child);
            }
        }
    }
}
