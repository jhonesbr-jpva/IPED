package iped.rcp.viewers.part;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Literal, case-insensitive match finding + one-line snippet building, shared by
 * the text viewer highlights ({@link RcpTextViewer}) and the hits list
 * ({@link HitsViewerPart}). Keeping both on this single implementation
 * guarantees identical match offsets, which the hits-list "go to occurrence"
 * navigation depends on. This is a pragmatic substitute for the legacy Lucene
 * fragment highlighter (no stemming/tokenization).
 */
final class TextHitFinder {

    /** Upper bound of occurrences kept (keeps the highlighter/table responsive). */
    static final int MAX_MATCHES = 5000;
    /** Terms shorter than this are skipped (1-char highlights are just noise). */
    static final int MIN_TERM_LEN = 2;

    private TextHitFinder() {
    }

    /** Sorted {start,end} ranges of every term occurrence in {@code text}. */
    static List<int[]> findMatches(String text, Set<String> terms, int maxMatches, int minTermLen) {
        List<int[]> found = new ArrayList<>();
        if (terms == null || terms.isEmpty() || text == null || text.isEmpty()) {
            return found;
        }
        String lower = text.toLowerCase();
        outer: for (String term : terms) {
            String t = sanitize(term);
            if (t.length() < minTermLen) {
                continue;
            }
            String tl = t.toLowerCase();
            int idx = 0;
            while ((idx = lower.indexOf(tl, idx)) >= 0) {
                found.add(new int[] { idx, idx + tl.length() });
                idx += tl.length();
                if (found.size() >= maxMatches) {
                    break outer;
                }
            }
        }
        found.sort(Comparator.comparingInt(a -> a[0]));
        return found;
    }

    /**
     * A trimmed, single-line snippet windowed around the match so the matched
     * term stays visible, capped at {@code maxLen} characters.
     */
    static String snippet(String text, int start, int end, int maxLen) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        int from = Math.max(lineStart, start - maxLen / 2);
        int to = Math.min(lineEnd, from + maxLen);
        String s = text.substring(from, to).replace('\t', ' ').replace('\r', ' ').trim();
        if (s.isEmpty()) {
            s = text.substring(start, Math.min(end, text.length())).trim();
        }
        return s;
    }

    /** Highlight terms may carry wildcards/quotes; match the literal core. */
    static String sanitize(String term) {
        if (term == null) {
            return ""; //$NON-NLS-1$
        }
        return term.replace("*", "").replace("?", "").replace("\"", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
