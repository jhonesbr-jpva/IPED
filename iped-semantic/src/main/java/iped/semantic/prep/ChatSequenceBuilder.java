package iped.semantic.prep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import iped.semantic.store.SequenceLine;
import iped.semantic.store.StagingStore.Message;

/**
 * Builds the conversation sequences of one chat (research D7, data-model E4).
 *
 * <ul>
 * <li>Order: {@code Communication:Date}, then {@code parentViewPosition}, then item id; messages
 * without a date go last.</li>
 * <li>A gap longer than {@code maxGapMinutes} breaks the conversation: another day's talk does not
 * complete this one's meaning.</li>
 * <li>Windows of up to {@code windowMessages} messages, every {@code stride} messages, up to
 * {@code maxChars} characters (a single longer message still forms its window).</li>
 * <li>A short message ({@code < shortChars}) never forms a unit on its own: when a gap isolates it,
 * it joins the neighbouring segment of the same chat, and a window never ends up holding one short
 * message alone while the chat has more to offer. A chat whose only content is one short message is
 * that conversation, and is kept.</li>
 * <li>Messages without text (media without caption) do not enter sequences.</li>
 * </ul>
 */
public final class ChatSequenceBuilder {

    private static final long MINUTE = 60_000L;

    private final int windowMessages;
    private final int stride;
    private final int maxChars;
    private final long maxGapMillis;
    private final int shortChars;

    public ChatSequenceBuilder(int windowMessages, int stride, int maxChars, int maxGapMinutes, int shortChars) {
        this.windowMessages = Math.max(1, windowMessages);
        this.stride = Math.max(1, stride);
        this.maxChars = maxChars;
        this.maxGapMillis = maxGapMinutes * MINUTE;
        this.shortChars = shortChars;
    }

    /** One window: its ordinal in the chat and its lines, in conversation order. */
    public static final class Window {
        private final int ordinal;
        private final List<SequenceLine> lines;

        Window(int ordinal, List<SequenceLine> lines) {
            this.ordinal = ordinal;
            this.lines = Collections.unmodifiableList(lines);
        }

        public int ordinal() {
            return ordinal;
        }

        public List<SequenceLine> lines() {
            return lines;
        }

        /** What the model reads: one {@code sender: text} line per message. */
        public String embeddingText() {
            StringBuilder sb = new StringBuilder();
            for (SequenceLine l : lines) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(l.embeddingText());
            }
            return sb.toString();
        }
    }

    public static final Comparator<Message> CONVERSATION_ORDER = Comparator
            .comparing((Message m) -> m.date == null ? Long.MAX_VALUE : m.date)
            .thenComparing(m -> m.position == null ? Integer.MAX_VALUE : m.position)
            .thenComparingInt(m -> m.itemId);

    public List<Window> build(List<Message> messages) {
        List<Message> sorted = new ArrayList<>();
        for (Message m : messages) {
            if (!m.text.isBlank()) {
                sorted.add(m);
            }
        }
        sorted.sort(CONVERSATION_ORDER);

        List<List<Message>> segments = segments(sorted);
        List<Window> out = new ArrayList<>();
        int ordinal = 0;
        for (List<Message> seg : segments) {
            for (List<Message> w : windows(seg)) {
                List<SequenceLine> lines = new ArrayList<>(w.size());
                for (Message m : w) {
                    lines.add(new SequenceLine(m.itemId, m.sender, m.date, m.text));
                }
                out.add(new Window(ordinal++, lines));
            }
        }
        return out;
    }

    private boolean isShort(Message m) {
        return m.text.strip().length() < shortChars;
    }

    private List<List<Message>> segments(List<Message> sorted) {
        List<List<Message>> segs = new ArrayList<>();
        List<Message> cur = new ArrayList<>();
        Message prev = null;
        for (Message m : sorted) {
            if (prev != null && prev.date != null && m.date != null && m.date - prev.date > maxGapMillis) {
                segs.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(m);
            prev = m;
        }
        if (!cur.isEmpty()) {
            segs.add(cur);
        }
        // A lone short message joins the previous segment (or the next one, when it opens the chat).
        for (int i = 0; i < segs.size() && segs.size() > 1; i++) {
            List<Message> s = segs.get(i);
            if (s.size() == 1 && isShort(s.get(0))) {
                if (i > 0) {
                    segs.get(i - 1).add(s.get(0));
                } else {
                    segs.get(1).add(0, s.get(0));
                }
                segs.remove(i);
                i--;
            }
        }
        return segs;
    }

    private List<List<Message>> windows(List<Message> seg) {
        List<List<Message>> out = new ArrayList<>();
        int n = seg.size();
        for (int start = 0; start < n; start += stride) {
            List<Message> w = new ArrayList<>();
            int chars = 0;
            int i = start;
            while (i < n && w.size() < windowMessages) {
                int len = seg.get(i).text.length();
                boolean fits = w.isEmpty() || chars + len <= maxChars;
                // a short message never stays alone when a neighbour can join it
                boolean mustJoin = w.size() == 1 && isShort(w.get(0));
                if (!fits && !mustJoin) {
                    break;
                }
                w.add(seg.get(i));
                chars += len;
                i++;
            }
            out.add(w);
            if (i >= n) {
                break;
            }
            // never let the next window start past the end of this one: no message may be skipped
            if (start + stride > i) {
                start = i - stride;
            }
        }
        // a trailing short message alone joins the previous window
        if (out.size() > 1) {
            List<Message> last = out.get(out.size() - 1);
            if (last.size() == 1 && isShort(last.get(0))) {
                out.remove(out.size() - 1);
                List<Message> prev = out.get(out.size() - 1);
                if (!prev.contains(last.get(0))) {
                    prev.add(last.get(0));
                }
            }
        }
        return out;
    }
}
