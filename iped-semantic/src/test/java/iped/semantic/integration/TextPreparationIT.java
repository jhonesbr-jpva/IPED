package iped.semantic.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.junit.BeforeClass;
import org.junit.Test;

import iped.engine.task.index.IndexItem;
import iped.properties.BasicProps;
import iped.properties.ExtraProperties;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.search.CaseItems;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.CoverageReason;
import iped.semantic.store.SemanticFields;
import iped.semantic.store.SemanticIndexReader;
import iped.semantic.store.SemanticStore;
import iped.semantic.store.SequenceLine;

/**
 * The text preparation on a real processing run (US1): the reference evidence processed by the
 * release with the text modality on, against the same evidence processed with it off
 * ({@link ProcessedCases}).
 */
public class TextPreparationIT {

    private static ProcessedCases cases;
    private static SemanticStore store;
    private static BuildRecord record;

    @BeforeClass
    public static void process() throws Exception {
        cases = ProcessedCases.get();
        store = SemanticStore.of(new File(cases.on, "iped"));
        record = BuildRecord.read(store.buildRecord());
    }

    @Test
    public void thePreparationCompletesAndCountsEveryItem() throws Exception {
        assertNotNull("the processed case has a build record", record);
        BuildRecord.ModalityRecord text = record.get(Modality.TEXT);
        assertEquals("despite the injected failure, the processing completes (FR-022)", BuildRecord.State.COMPLETE,
                text.state);
        long counted = text.counts.values().stream().mapToLong(Long::longValue).sum();
        try (IndexReader main = mainIndex(cases.on)) {
            assertEquals("the counts add up to the items considered: " + text.counts,
                    CaseItems.of(main).cardinality(), counted);
        }
        assertFalse("no credential in the build record (FR-045)", record.toJson().toLowerCase().contains("secret"));
    }

    @Test
    public void everyPassageIsTheLiteralRangeOfItsItemText() throws Exception {
        Map<String, String> files = evidenceTexts(cases.evidence.toPath());
        Map<Integer, Integer> shifts = new HashMap<>();
        int checked = 0;
        try (IndexReader main = mainIndex(cases.on);
                SemanticIndexReader semantic = new SemanticIndexReader(store.index(Modality.TEXT))) {
            Map<Integer, Document> items = itemsById(main);
            Bits liveDocs = MultiBits.getLiveDocs(semantic.reader());
            for (int doc = 0; doc < semantic.reader().maxDoc(); doc++) {
                if (liveDocs != null && !liveDocs.get(doc)) {
                    continue; // replaced by a later write of the same item
                }
                Document d = semantic.document(doc);
                if (!SemanticFields.KIND_PASSAGE.equals(d.get(SemanticFields.KIND))) {
                    continue;
                }
                Document item = items.get(SemanticIndexReader.intField(d, SemanticFields.ITEM_ID, -1));
                String content = item == null ? null : files.get(item.get(BasicProps.NAME));
                if (content == null) {
                    continue; // not a plain text file of the evidence: its text cannot be read back here
                }
                int start = SemanticIndexReader.intField(d, SemanticFields.CHAR_START, -1);
                int end = SemanticIndexReader.intField(d, SemanticFields.CHAR_END, -1);
                // Offsets are over the text IPED extracted, which is the file's content after a
                // prefix the extraction adds; the text itself must be the file's, literally, and one
                // prefix must place every passage of the item.
                String text = d.get(SemanticFields.TEXT);
                String what = "SC-006: passage of " + item.get(BasicProps.NAME) + " [" + start + ", " + end + ")";
                int at = content.indexOf(text);
                assertTrue(what + " is literal text of the file: <" + text + ">", at >= 0);
                assertEquals(what + " spans exactly its text", text.length(), end - start);
                int itemId = SemanticIndexReader.intField(d, SemanticFields.ITEM_ID, -1);
                Integer shift = shifts.putIfAbsent(itemId, start - at);
                if (shift != null) {
                    assertEquals(what + " is placed by the same prefix as the item's other passages", (int) shift,
                            start - at);
                }
                checked++;
            }
        }
        assertTrue("at least one passage of a text file was checked", checked > 0);
    }

    @Test
    public void everySequenceLineIsTheBodyOfItsMessageAndTheChatIsCoveredByThem() throws Exception {
        int lines = 0;
        try (IndexReader main = mainIndex(cases.on);
                SemanticIndexReader semantic = new SemanticIndexReader(store.index(Modality.TEXT))) {
            Map<Integer, Document> items = itemsById(main);
            Map<Integer, List<String>> kindsByKey = new HashMap<>();
            Bits liveDocs = MultiBits.getLiveDocs(semantic.reader());
            for (int doc = 0; doc < semantic.reader().maxDoc(); doc++) {
                if (liveDocs != null && !liveDocs.get(doc)) {
                    continue; // replaced by a later write of the same item
                }
                Document d = semantic.document(doc);
                int key = SemanticIndexReader.intField(d, SemanticFields.ITEM_ID, -1);
                String kind = d.get(SemanticFields.KIND);
                kindsByKey.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(SemanticFields.KIND_STATUS.equals(kind) ? d.get(SemanticFields.REASON) : kind);
                if (!SemanticFields.KIND_SEQUENCE.equals(kind)) {
                    continue;
                }
                for (SequenceLine line : SemanticIndexReader.lines(d)) {
                    Document message = items.get(line.getItemId());
                    assertNotNull("the message of a line exists in the main index", message);
                    // The line keeps the parser's value as it came; the main index stores it trimmed.
                    assertEquals("SC-006: line of message " + line.getItemId(), body(message).strip(),
                            line.getText().strip());
                    lines++;
                }
            }
            org.junit.Assume.assumeTrue("the reference evidence has no conversation", lines > 0);
            for (Map.Entry<Integer, List<String>> e : kindsByKey.entrySet()) {
                if (e.getValue().contains(SemanticFields.KIND_SEQUENCE)) {
                    assertTrue("chat " + e.getKey() + " is declared covered by its messages: " + e.getValue(),
                            e.getValue().contains(CoverageReason.COVERED_BY_MESSAGES.code()));
                    assertFalse("and has no passage of its own: " + e.getValue(),
                            e.getValue().contains(SemanticFields.KIND_PASSAGE));
                }
            }
        }
    }

    @Test
    public void theInjectedFailureIsDeclaredAndTheOtherTextsAreCovered() throws Exception {
        try (IndexReader main = mainIndex(cases.on);
                SemanticIndexReader semantic = new SemanticIndexReader(store.index(Modality.TEXT))) {
            Map<Integer, Document> items = itemsById(main);
            Map<Integer, String> reasons = new HashMap<>();
            Bits liveDocs = MultiBits.getLiveDocs(semantic.reader());
            for (int doc = 0; doc < semantic.reader().maxDoc(); doc++) {
                if (liveDocs != null && !liveDocs.get(doc)) {
                    continue; // replaced by a later write of the same item
                }
                Document d = semantic.document(doc);
                if (SemanticFields.KIND_STATUS.equals(d.get(SemanticFields.KIND))) {
                    reasons.put(SemanticIndexReader.intField(d, SemanticFields.ITEM_ID, -1), d.get(SemanticFields.REASON));
                }
            }
            boolean sawFailure = false;
            boolean sawCoveredText = false;
            for (Map.Entry<Integer, Document> e : items.entrySet()) {
                String name = e.getValue().get(BasicProps.NAME);
                if (name == null || !name.toLowerCase().endsWith(".txt")) {
                    continue;
                }
                String reason = reasons.get(e.getKey());
                if (cases.failedItem.equals(name)) {
                    assertEquals(name + " was made to fail", CoverageReason.PREP_FAILURE.code(), reason);
                    sawFailure = true;
                } else if (CoverageReason.COVERED.code().equals(reason)) {
                    sawCoveredText = true;
                }
            }
            assertTrue("the failing item was found", sawFailure);
            assertTrue("the other text files are covered", sawCoveredText);
        }
    }

    @Test
    public void theMainIndexGainsNoField() throws Exception {
        // FR-001/FR-047: the capability writes under iped/semantic only
        assertEquals(fieldNames(cases.off), fieldNames(cases.on));
        assertFalse("processing with the capability off leaves no semantic folder (FR-027)",
                new File(cases.off, "iped/semantic").exists());
    }

    // ---------------------------------------------------------------------------------------------

    static IndexReader mainIndex(File caseDir) throws Exception {
        return DirectoryReader.open(FSDirectory.open(new File(caseDir, "iped/index").toPath()));
    }

    /** Stored documents of the main index by item id (the first document of each id). */
    static Map<Integer, Document> itemsById(IndexReader main) throws Exception {
        Map<Integer, Document> out = new HashMap<>();
        for (LeafReaderContext ctx : main.leaves()) {
            NumericDocValues ids = ctx.reader().getNumericDocValues(IndexItem.ID);
            if (ids == null) {
                continue;
            }
            Bits live = ctx.reader().getLiveDocs();
            for (int doc = ids.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = ids.nextDoc()) {
                if (live == null || live.get(doc)) {
                    out.putIfAbsent((int) ids.longValue(), ctx.reader().document(doc));
                }
            }
        }
        return out;
    }

    private static TreeSet<String> fieldNames(File caseDir) throws Exception {
        TreeSet<String> names = new TreeSet<>();
        try (IndexReader main = mainIndex(caseDir)) {
            FieldInfos infos = FieldInfos.getMergedFieldInfos(main);
            for (FieldInfo fi : infos) {
                names.add(fi.name);
            }
        }
        return names;
    }

    /** The literal body as the preparation reads it: every Message-Body value but the type markers. */
    private static String body(Document message) {
        StringBuilder sb = new StringBuilder();
        for (String v : message.getValues(ExtraProperties.MESSAGE_BODY)) {
            if (v == null || v.strip().matches("^! (?:[A-Z0-9_]+|Attachment: \\S+)$")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /** Contents of the evidence's .txt files by name, UTF-8. */
    private static Map<String, String> evidenceTexts(Path evidence) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (Stream<Path> files = Files.walk(evidence)) {
            for (Path p : files.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .collect(Collectors.toList())) {
                out.put(p.getFileName().toString(), new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
