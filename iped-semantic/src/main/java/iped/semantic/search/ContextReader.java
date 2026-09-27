package iped.semantic.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;

import iped.semantic.store.SemanticFields;
import iped.semantic.store.SemanticIndexReader;
import iped.semantic.store.SequenceLine;

/**
 * Literal text around an entry, read from the semantic index without re-extracting the item
 * (data-model E12, research D21): the neighbouring passages, stitched by their offsets so the
 * overlap is not repeated - the result is exactly {@code [charStart, charEnd)} of the item text - or
 * the neighbouring messages of a conversation.
 */
public final class ContextReader {

    private ContextReader() {
    }

    /** Context of one entry. */
    public static final class Context {
        private final int itemId;
        private final String kind;
        private final int charStart;
        private final int charEnd;
        private final String text;
        private final List<SequenceLine> messages;
        private final boolean truncated;

        Context(int itemId, String kind, int charStart, int charEnd, String text, List<SequenceLine> messages,
                boolean truncated) {
            this.itemId = itemId;
            this.kind = kind;
            this.charStart = charStart;
            this.charEnd = charEnd;
            this.text = text;
            this.messages = messages;
            this.truncated = truncated;
        }

        public int getItemId() {
            return itemId;
        }

        public String getKind() {
            return kind;
        }

        public int getCharStart() {
            return charStart;
        }

        public int getCharEnd() {
            return charEnd;
        }

        /** Stitched literal text; {@code null} for a sequence. */
        public String getText() {
            return text;
        }

        /** Messages around a sequence entry; {@code null} for a passage. */
        public List<SequenceLine> getMessages() {
            return messages;
        }

        /** The {@code contextMaxChars} ceiling cut the context. */
        public boolean isTruncated() {
            return truncated;
        }
    }

    /** A stored passage: its ordinal, offsets and literal text. */
    static final class Stored {
        final int ordinal;
        final int start;
        final int end;
        final String text;
        final String kind;

        Stored(int ordinal, int start, int end, String text, String kind) {
            this.ordinal = ordinal;
            this.start = start;
            this.end = end;
            this.text = text;
            this.kind = kind;
        }
    }

    /** Stored documents keyed by an item, by kind; empty when the item has none. */
    static List<Document> documentsOf(SemanticIndexReader index, int itemId) throws IOException {
        TopDocs td = index.searcher().search(new TermQuery(new Term(SemanticFields.ITEM_ID_KEY, Integer.toString(itemId))),
                Integer.MAX_VALUE);
        List<Document> out = new ArrayList<>();
        for (ScoreDoc sd : td.scoreDocs) {
            out.add(index.document(sd.doc));
        }
        return out;
    }

    static List<Stored> passagesOf(SemanticIndexReader index, int itemId, String kind) throws IOException {
        List<Stored> out = new ArrayList<>();
        for (Document d : documentsOf(index, itemId)) {
            if (kind.equals(d.get(SemanticFields.KIND))) {
                out.add(new Stored(SemanticIndexReader.intField(d, SemanticFields.ORDINAL, 0),
                        SemanticIndexReader.intField(d, SemanticFields.CHAR_START, 0),
                        SemanticIndexReader.intField(d, SemanticFields.CHAR_END, 0), d.get(SemanticFields.TEXT), kind));
            }
        }
        out.sort(Comparator.comparingInt((Stored s) -> s.start).thenComparingInt(s -> s.ordinal));
        return out;
    }

    /**
     * Stitches consecutive passages into the literal text of {@code [first.start, last.end)}, stopping
     * at a gap (which the chunker only leaves over whitespace it does not store).
     */
    static String stitch(List<Stored> passages, int[] range) {
        StringBuilder sb = new StringBuilder();
        int start = -1;
        int end = -1;
        for (Stored p : passages) {
            if (start < 0) {
                start = p.start;
                end = p.end;
                sb.append(p.text);
                continue;
            }
            if (p.start > end) {
                break; // a gap: stop, the text returned must stay an exact interval
            }
            if (p.end > end) {
                sb.append(p.text, end - p.start, p.text.length());
                end = p.end;
            }
        }
        range[0] = start;
        range[1] = end;
        return sb.toString();
    }

    /**
     * The literal text of {@code [start, end)} of an item when its stored passages cover it
     * contiguously; {@code null} otherwise.
     */
    public static String textRange(SemanticIndexReader index, int itemId, int start, int end) throws IOException {
        for (String kind : new String[] { SemanticFields.KIND_PASSAGE, SemanticFields.KIND_TRANSCRIPT }) {
            List<Stored> covering = new ArrayList<>();
            for (Stored p : passagesOf(index, itemId, kind)) {
                if (p.end > start && p.start < end) {
                    covering.add(p);
                }
            }
            if (covering.isEmpty()) {
                continue;
            }
            int[] r = new int[2];
            String text = stitch(covering, r);
            if (r[0] <= start && r[1] >= end) {
                return text.substring(start - r[0], end - r[0]);
            }
        }
        return null;
    }

    /**
     * Context around the entry {@code (itemId, ordinal)}: {@code before} and {@code after}
     * passages (or windows of a conversation) on each side, capped at {@code maxChars}.
     *
     * @return {@code null} when the index has no such entry
     */
    public static Context around(SemanticIndexReader index, int itemId, int ordinal, int before, int after,
            int maxChars) throws IOException {
        List<Document> docs = documentsOf(index, itemId);
        Document anchor = null;
        for (Document d : docs) {
            String k = d.get(SemanticFields.KIND);
            if (!SemanticFields.KIND_STATUS.equals(k) && SemanticIndexReader.intField(d, SemanticFields.ORDINAL, -1) == ordinal) {
                anchor = d;
                break;
            }
        }
        if (anchor == null) {
            return null;
        }
        String kind = anchor.get(SemanticFields.KIND);
        if (SemanticFields.KIND_SEQUENCE.equals(kind)) {
            return messagesAround(docs, itemId, ordinal, before, after, maxChars);
        }
        List<Stored> all = passagesOf(index, itemId, kind);
        all.sort(Comparator.comparingInt(s -> s.ordinal));
        int at = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).ordinal == ordinal) {
                at = i;
            }
        }
        List<Stored> window = new ArrayList<>(all.subList(Math.max(0, at - Math.max(0, before)),
                Math.min(all.size(), at + Math.max(0, after) + 1)));
        window.sort(Comparator.comparingInt(s -> s.start));
        int[] r = new int[2];
        String text = stitch(window, r);
        boolean truncated = false;
        if (text.length() > maxChars) {
            // keep the anchor passage centred in what fits
            Stored a = all.get(at);
            int center = (a.start + a.end) / 2 - r[0];
            int from = Math.max(0, Math.min(center - maxChars / 2, text.length() - maxChars));
            text = text.substring(from, from + maxChars);
            r[0] += from;
            r[1] = r[0] + maxChars;
            truncated = true;
        }
        return new Context(itemId, kind, r[0], r[1], text, null, truncated);
    }

    private static Context messagesAround(List<Document> docs, int chatId, int ordinal, int before, int after,
            int maxChars) {
        List<Document> windows = new ArrayList<>();
        for (Document d : docs) {
            if (SemanticFields.KIND_SEQUENCE.equals(d.get(SemanticFields.KIND))) {
                windows.add(d);
            }
        }
        windows.sort(Comparator.comparingInt(d -> SemanticIndexReader.intField(d, SemanticFields.ORDINAL, 0)));
        Map<Integer, SequenceLine> lines = new LinkedHashMap<>();
        for (Document d : windows) {
            int o = SemanticIndexReader.intField(d, SemanticFields.ORDINAL, 0);
            if (o >= ordinal - Math.max(0, before) && o <= ordinal + Math.max(0, after)) {
                for (SequenceLine l : SemanticIndexReader.lines(d)) {
                    lines.putIfAbsent(l.getItemId(), l);
                }
            }
        }
        List<SequenceLine> ordered = new ArrayList<>(lines.values());
        ordered.sort(Comparator.comparing((SequenceLine l) -> l.getDateMillis() == null ? Long.MAX_VALUE : l.getDateMillis())
                .thenComparingInt(SequenceLine::getItemId));
        int total = 0;
        List<SequenceLine> kept = new ArrayList<>();
        boolean truncated = false;
        for (SequenceLine l : ordered) {
            total += l.getText().length();
            if (total > maxChars && !kept.isEmpty()) {
                truncated = true;
                break;
            }
            kept.add(l);
        }
        return new Context(chatId, SemanticFields.KIND_SEQUENCE, -1, -1, null, Collections.unmodifiableList(kept),
                truncated);
    }
}
