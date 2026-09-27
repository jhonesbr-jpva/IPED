package iped.semantic.prep;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits an item's text into overlapping passages (research D6, data-model E3).
 *
 * <ul>
 * <li>The metadata header IPED's parser appends at the end of every text ({@code StandardParser.METADATA_HEADER},
 * localized) is not content and is cut, at its <b>last</b> occurrence.</li>
 * <li>Windows of about {@code passageChars}, overlapping by up to {@code overlapChars}, ending at a
 * sentence boundary when one lies in the second half of the window, else at whitespace, else at the
 * window size.</li>
 * <li>Only the first {@code maxChars} characters are prepared; the excess is counted and declared
 * ({@code COVERED_TRUNCATED}).</li>
 * </ul>
 *
 * <b>Invariant (SC-006):</b> every passage's text is exactly {@code text.substring(start, end)} of the
 * item text after the header cut; offsets count from the first character of the item text, which
 * is what the text viewer shows.
 */
public final class PassageChunker {

    /** Characters kept after the limit to find the header when the text is longer than the limit. */
    private static final int TAIL_CHARS = 1 << 20;

    private final int passageChars;
    private final int overlapChars;
    private final int maxChars;

    public PassageChunker(int passageChars, int overlapChars, int maxChars) {
        if (overlapChars >= passageChars) {
            throw new IllegalArgumentException("overlap must be smaller than the passage");
        }
        this.passageChars = passageChars;
        this.overlapChars = overlapChars;
        this.maxChars = maxChars;
    }

    /** One passage: ordinal from 0, offsets in the item text, and the literal text. */
    public static final class Passage {
        private final int ordinal;
        private final int start;
        private final int end;
        private final String text;

        Passage(int ordinal, int start, int end, String text) {
            this.ordinal = ordinal;
            this.start = start;
            this.end = end;
            this.text = text;
        }

        public int ordinal() {
            return ordinal;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public String text() {
            return text;
        }
    }

    /** Passages of an item, and whether its text was cut at the limit. */
    public static final class Result {
        private final List<Passage> passages;
        private final long excessChars;
        private final String content;

        Result(List<Passage> passages, long excessChars, String content) {
            this.passages = Collections.unmodifiableList(passages);
            this.excessChars = excessChars;
            this.content = content;
        }

        /** The text the passages were cut from: after the header cut, up to the limit. Offsets refer to it. */
        public String content() {
            return content;
        }

        public List<Passage> passages() {
            return passages;
        }

        public boolean truncated() {
            return excessChars > 0;
        }

        /** Content characters beyond {@code maxTextCharsPerItem} that were not prepared. */
        public long excessChars() {
            return excessChars;
        }
    }

    /**
     * Reads the whole text (to find the trailing header), keeping only the first {@code maxChars}
     * characters of content plus a bounded tail.
     *
     * @param metadataHeader
     *            the parser's header in the processing locale; {@code null} if none
     */
    public Result chunk(Reader reader, String metadataHeader) throws IOException {
        long headCap = (long) maxChars + TAIL_CHARS;
        StringBuilder head = new StringBuilder((int) Math.min(headCap, 1 << 16));
        StringBuilder tail = new StringBuilder();
        long total = 0;
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) != -1) {
            int toHead = (int) Math.max(0, Math.min(n, headCap - head.length()));
            head.append(buf, 0, toHead);
            if (toHead < n) {
                tail.append(buf, toHead, n - toHead);
                if (tail.length() > 2 * TAIL_CHARS) {
                    tail.delete(0, tail.length() - TAIL_CHARS);
                }
            }
            total += n;
        }

        // Where content ends: the last header, if any. In a text longer than the head, the header
        // (always the very end) is in the tail.
        long contentLength = total;
        if (metadataHeader != null && !metadataHeader.isEmpty()) {
            if (tail.length() > 0) {
                int inTail = tail.lastIndexOf(metadataHeader);
                if (inTail >= 0) {
                    contentLength = total - (tail.length() - inTail);
                }
            } else {
                int inHead = head.lastIndexOf(metadataHeader);
                if (inHead >= 0) {
                    contentLength = inHead;
                }
            }
        }
        // The parser writes a line break before the header; trailing whitespace is not content.
        long contentEnd = contentLength;
        if (contentLength <= head.length()) {
            int end = (int) contentLength;
            while (end > 0 && Character.isWhitespace(head.charAt(end - 1))) {
                end--;
            }
            contentEnd = end;
        }
        String content = head.substring(0, (int) Math.min(contentEnd, maxChars));
        long excess = Math.max(0, contentEnd - maxChars);
        return new Result(split(content), excess, content);
    }

    /** Chunks an in-memory text that has no header. */
    public List<Passage> split(String text) {
        List<Passage> out = new ArrayList<>();
        int len = text.length();
        int start = skipWhitespace(text, 0);
        int ordinal = 0;
        while (start < len) {
            int end;
            if (len - start <= passageChars) {
                end = len;
            } else {
                end = boundary(text, start, start + passageChars);
            }
            int trimmedEnd = end;
            while (trimmedEnd > start && Character.isWhitespace(text.charAt(trimmedEnd - 1))) {
                trimmedEnd--;
            }
            if (trimmedEnd > start) {
                out.add(new Passage(ordinal++, start, trimmedEnd, text.substring(start, trimmedEnd)));
            }
            if (end >= len) {
                break;
            }
            int next = nextStart(text, start, end);
            start = skipWhitespace(text, next);
        }
        return out;
    }

    /** Best cut in {@code (start, limit]}: sentence end in the second half, else whitespace, else limit. */
    private int boundary(String text, int start, int limit) {
        int half = start + passageChars / 2;
        for (int i = limit; i > half; i--) {
            char c = text.charAt(i - 1);
            if ((c == '.' || c == '!' || c == '?' || c == '\n' || c == '…') && Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        for (int i = limit; i > half; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return limit;
    }

    /** Next window starts up to {@code overlapChars} before {@code end}, at the start of a word. */
    private int nextStart(String text, int start, int end) {
        int candidate = Math.max(start + 1, end - overlapChars);
        int i = candidate;
        while (i < end && !Character.isWhitespace(text.charAt(i - 1))) {
            i++;
        }
        return i < end ? i : Math.max(start + 1, end - overlapChars);
    }

    private static int skipWhitespace(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
