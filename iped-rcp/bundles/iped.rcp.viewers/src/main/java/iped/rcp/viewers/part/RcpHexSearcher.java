package iped.rcp.viewers.part;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.exbin.deltahex.highlight.swing.HighlightCodeAreaPainter;
import org.exbin.deltahex.highlight.swing.HighlightCodeAreaPainter.SearchMatch;
import org.exbin.deltahex.swing.CodeArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.io.SeekableInputStream;
import iped.rcp.core.i18n.Messages;
import iped.viewers.HexViewerPlus.HexSearcher;
import iped.viewers.HexViewerPlus.Hits;

/**
 * App-free {@link HexSearcher} for the bridged Hex viewer part. Reproduces the
 * byte/text search of the legacy {@code iped.app.ui.viewers.HexSearcherImpl}
 * (KMP over a sliding buffer, string or hex terms, optional case-insensitive
 * match) but WITHOUT the Swing {@code App} singleton coupling its
 * {@code ProgressDialog} brings — the reason the hex viewer was deferred in the
 * RCP migration. The search runs on a background daemon thread (superseded
 * searches are dropped) and the painter/caret/result-label updates are
 * marshalled back to the EDT. In-search progress/cancel dialog is intentionally
 * out of scope here (this bridged viewer is provisional, pending a native SWT
 * rewrite).
 */
public class RcpHexSearcher implements HexSearcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RcpHexSearcher.class);

    /** Hard cap on accumulated matches, mirroring the legacy searcher. */
    private static final int MAX_TERMS = 10000;

    private final AtomicLong searchStamp = new AtomicLong();

    @Override
    public void doSearch(CodeArea codeArea, HighlightCodeAreaPainter painter, Hits hits, SeekableInputStream data,
            Charset charset, Set<String> highlightTerms, long offset, boolean searchString, boolean ignoreCaseSearch,
            JLabel resultSearch, int maxHits) throws Exception {

        long stamp = searchStamp.incrementAndGet();
        Thread worker = new Thread(() -> {
            try {
                List<SearchMatch> matches = search(data, charset, highlightTerms, offset, searchString,
                        ignoreCaseSearch, maxHits, stamp);
                if (stamp != searchStamp.get()) {
                    return; // a newer search won
                }
                SwingUtilities.invokeLater(() -> applyMatches(codeArea, painter, hits, resultSearch, matches, stamp));
            } catch (Exception e) {
                LOGGER.warn("Error searching in the hex viewer", e);
            }
        }, "rcp-hex-search");
        worker.setDaemon(true);
        worker.start();
    }

    private List<SearchMatch> search(SeekableInputStream data, Charset charset, Set<String> highlightTerms, long offset,
            boolean searchString, boolean ignoreCaseSearch, int maxHits, long stamp) throws Exception {

        List<SearchMatch> found = new ArrayList<>();
        Set<String> terms = new HashSet<>(highlightTerms);

        int maxTermLength = 0;
        for (String term : terms) {
            maxTermLength = Math.max(maxTermLength, term.length());
        }

        long dataSize = data.size();
        int bufferLength = Math.max(4 * 1024, maxTermLength);
        byte[] buffer = new byte[bufferLength + maxTermLength];
        long position = offset;

        while (position < dataSize - bufferLength) {
            if (stamp != searchStamp.get()) {
                return found; // superseded
            }
            int chunk = bufferLength + maxTermLength;
            data.seek(position);
            int read = data.readNBytes(buffer, 0, chunk);
            if (read > 0) {
                for (String term : terms) {
                    byte[] needle = searchString ? term.getBytes(charset) : hexStringToByteArray(term);
                    for (int off : searchBytes(needle, buffer, bufferLength + term.length() - 1,
                            searchString && ignoreCaseSearch)) {
                        SearchMatch match = new SearchMatch();
                        match.setPosition(position + off);
                        match.setLength(searchString ? term.length() : needle.length);
                        if (found.size() < MAX_TERMS) {
                            found.add(match);
                        }
                        if (found.size() == maxHits) {
                            return finishSorted(found);
                        }
                    }
                }
            }
            position += bufferLength;
        }

        // Search the trailing bytes left beyond the last full buffer.
        int tail = (int) (dataSize - position);
        if (tail > 0) {
            data.seek(position);
            int read = data.readNBytes(buffer, 0, tail);
            if (read > 0) {
                for (String term : terms) {
                    byte[] needle = searchString ? term.getBytes(charset) : hexStringToByteArray(term);
                    for (int off : searchBytes(needle, buffer, tail, searchString && ignoreCaseSearch)) {
                        SearchMatch match = new SearchMatch();
                        match.setPosition(position + off);
                        match.setLength(searchString ? term.length() : needle.length);
                        if (found.size() < MAX_TERMS) {
                            found.add(match);
                        }
                        if (found.size() == maxHits) {
                            return finishSorted(found);
                        }
                    }
                }
            }
        }
        return finishSorted(found);
    }

    private static List<SearchMatch> finishSorted(List<SearchMatch> found) {
        found.sort((a, b) -> Long.compare(a.getPosition(), b.getPosition()));
        return found;
    }

    private void applyMatches(CodeArea codeArea, HighlightCodeAreaPainter painter, Hits hits, JLabel resultSearch,
            List<SearchMatch> matches, long stamp) {
        if (stamp != searchStamp.get()) {
            return;
        }
        painter.clearMatches();
        painter.setMatches(matches);
        hits.totalHits = matches.size();
        if (hits.totalHits > 0) {
            hits.currentHit = 0;
            painter.setCurrentMatchIndex(hits.currentHit);
            SearchMatch first = painter.getCurrentMatch();
            codeArea.revealPosition(first.getPosition(), codeArea.getActiveSection());
            codeArea.setCaretPosition(first.getPosition() + first.getLength());
            resultSearch.setText((hits.currentHit + 1) + " / " + hits.totalHits);
        } else {
            resultSearch.setText(Messages.getString("RcpHexSearch.noHits"));
        }
        codeArea.repaint();
    }

    private static byte[] hexStringToByteArray(String s) {
        s = s.replace(" ", "");
        if (s.length() % 2 != 0) {
            s = "0" + s;
        }
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * KMP search for all occurrences of {@code word} in the first {@code length}
     * bytes of {@code sentence}. Ported from the legacy {@code HexSearcherImpl}
     * (O(n+k); the buffer length is variable, hence the explicit {@code length}).
     */
    private static List<Integer> searchBytes(byte[] word, byte[] sentence, int length, boolean ignoreCaseSearch) {
        List<Integer> matchedIndices = new ArrayList<>();
        if (word.length == 0) {
            return matchedIndices;
        }
        int wordLength = word.length;
        int beginMatch = 0;
        int idxWord = 0;
        List<Integer> partialTable = createPartialMatchTable(word);
        while (beginMatch + idxWord < length) {
            byte wordChar;
            byte sentenceChar;
            if (ignoreCaseSearch) {
                wordChar = (byte) Character.toUpperCase((char) word[idxWord]);
                sentenceChar = (byte) Character.toUpperCase((char) sentence[beginMatch + idxWord]);
            } else {
                wordChar = word[idxWord];
                sentenceChar = sentence[beginMatch + idxWord];
            }
            if (wordChar == sentenceChar) {
                if (idxWord == wordLength - 1) {
                    matchedIndices.add(beginMatch);
                    beginMatch = beginMatch + idxWord - partialTable.get(idxWord);
                    idxWord = partialTable.get(idxWord) > -1 ? partialTable.get(idxWord) : 0;
                } else {
                    idxWord++;
                }
            } else {
                beginMatch = beginMatch + idxWord - partialTable.get(idxWord);
                idxWord = partialTable.get(idxWord) > -1 ? partialTable.get(idxWord) : 0;
            }
        }
        return matchedIndices;
    }

    private static List<Integer> createPartialMatchTable(byte[] word) {
        if (word.length == 0) {
            return Collections.emptyList();
        }
        List<Integer> partialTable = new ArrayList<>(word.length + 1);
        partialTable.add(-1);
        partialTable.add(0);
        byte firstChar = word[0];
        for (int idx = 1; idx < word.length; idx++) {
            int prevVal = partialTable.get(idx);
            if (prevVal == 0) {
                partialTable.add(word[idx] == firstChar ? 1 : 0);
            } else if (word[idx] == word[prevVal]) {
                partialTable.add(prevVal + 1);
            } else {
                partialTable.add(0);
            }
        }
        return partialTable;
    }
}
