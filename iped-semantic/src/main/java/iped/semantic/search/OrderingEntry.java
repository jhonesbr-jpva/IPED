package iped.semantic.search;

import java.util.Collections;
import java.util.List;

/**
 * One entry of an ordering (data-model E8). What justifies it is always literal content: the
 * passage text of the item, the messages of the conversation as they are, or the frame of the video
 * - never a generated description (FR-006, FR-035). No vector and no raw score leaves through here:
 * {@link #getProximity()} is the relative proximity (cosine, or fused rank score in hybrid mode),
 * labelled by {@link #getProximityKind()}.
 */
public final class OrderingEntry {

    /** How to read the proximity (FR-010, FR-047). */
    public enum ProximityKind {
        RELATIVE("relative"), COMBINED_RELATIVE("combined_relative");

        private final String code;

        ProximityKind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** A literal passage of the item: {@code text == itemText.substring(charStart, charEnd)}. */
    public static final class Excerpt {
        private final int ordinal;
        private final int charStart;
        private final int charEnd;
        private final String text;

        public Excerpt(int ordinal, int charStart, int charEnd, String text) {
            this.ordinal = ordinal;
            this.charStart = charStart;
            this.charEnd = charEnd;
            this.text = text;
        }

        public int getOrdinal() {
            return ordinal;
        }

        public int getCharStart() {
            return charStart;
        }

        public int getCharEnd() {
            return charEnd;
        }

        public String getText() {
            return text;
        }
    }

    /** A message of a sequence as displayed: item, sender, date and literal text. */
    public static final class MessageLine {
        private final int itemId;
        private final String sender;
        private final Long dateMillis;
        private final String date;
        private final String text;
        private final boolean inScope;

        public MessageLine(int itemId, String sender, Long dateMillis, String date, String text, boolean inScope) {
            this.itemId = itemId;
            this.sender = sender;
            this.dateMillis = dateMillis;
            this.date = date;
            this.text = text;
            this.inScope = inScope;
        }

        public int getItemId() {
            return itemId;
        }

        public String getSender() {
            return sender;
        }

        public Long getDateMillis() {
            return dateMillis;
        }

        /** ISO-8601 instant in UTC, or {@code null}. */
        public String getDate() {
            return date;
        }

        public String getText() {
            return text;
        }

        /**
         * Whether the message is in the question's scope. Messages outside it are shown as context,
         * are not counted and are not bookmarked with the entry (research D7).
         */
        public boolean isInScope() {
            return inScope;
        }
    }

    /** A conversation sequence entry. */
    public static final class Sequence {
        private final List<MessageLine> messages;
        private final int absorbedWindows;

        public Sequence(List<MessageLine> messages, int absorbedWindows) {
            this.messages = Collections.unmodifiableList(messages);
            this.absorbedWindows = absorbedWindows;
        }

        public List<MessageLine> getMessages() {
            return messages;
        }

        /** Overlapping windows of lower proximity folded into this one (FR-009). */
        public int getAbsorbedWindows() {
            return absorbedWindows;
        }
    }

    /** The frame of a video entry; the instant is always approximate (amendment A2). */
    public static final class Frame {
        private final int frameIndex;
        private final Integer frameCount;
        private final Long instantApproxMs;

        public Frame(int frameIndex, Integer frameCount, Long instantApproxMs) {
            this.frameIndex = frameIndex;
            this.frameCount = frameCount;
            this.instantApproxMs = instantApproxMs;
        }

        public int getFrameIndex() {
            return frameIndex;
        }

        public Integer getFrameCount() {
            return frameCount;
        }

        public Long getInstantApproxMs() {
            return instantApproxMs;
        }
    }

    private final int rank;
    private final double proximity;
    private final ProximityKind proximityKind;
    private final Integer semanticRank;
    private final Integer lexicalRank;
    private final String kind;
    private final int caseIndex;
    private final int itemId;
    private final int ordinal;
    private final List<Excerpt> excerpts;
    private final Sequence sequence;
    private final Frame frame;

    public OrderingEntry(int rank, double proximity, ProximityKind proximityKind, Integer semanticRank,
            Integer lexicalRank, String kind, int caseIndex, int itemId, int ordinal, List<Excerpt> excerpts,
            Sequence sequence, Frame frame) {
        this.rank = rank;
        this.proximity = proximity;
        this.proximityKind = proximityKind;
        this.semanticRank = semanticRank;
        this.lexicalRank = lexicalRank;
        this.kind = kind;
        this.caseIndex = caseIndex;
        this.itemId = itemId;
        this.ordinal = ordinal;
        this.excerpts = excerpts == null ? Collections.emptyList() : Collections.unmodifiableList(excerpts);
        this.sequence = sequence;
        this.frame = frame;
    }

    /** Position in the ordering, from 1. */
    public int getRank() {
        return rank;
    }

    public double getProximity() {
        return proximity;
    }

    public ProximityKind getProximityKind() {
        return proximityKind;
    }

    /** Hybrid only: position in the semantic list, {@code null} if absent from it. */
    public Integer getSemanticRank() {
        return semanticRank;
    }

    /** Hybrid only: position in the word list, {@code null} if absent from it. */
    public Integer getLexicalRank() {
        return lexicalRank;
    }

    /** {@code passage}, {@code transcript}, {@code sequence}, {@code image}, {@code video}. */
    public String getKind() {
        return kind;
    }

    /** Which open case the entry belongs to (0 with a single case). */
    public int getCaseIndex() {
        return caseIndex;
    }

    /** The item; for a sequence, the chat. */
    public int getItemId() {
        return itemId;
    }

    /** Ordinal of the best passage, window or frame. */
    public int getOrdinal() {
        return ordinal;
    }

    public List<Excerpt> getExcerpts() {
        return excerpts;
    }

    public Sequence getSequence() {
        return sequence;
    }

    public Frame getFrame() {
        return frame;
    }

    /** Item ids an action on this entry targets: the item, or the in-scope messages of a sequence. */
    public List<Integer> targetItemIds() {
        if (sequence == null) {
            return List.of(itemId);
        }
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (MessageLine m : sequence.getMessages()) {
            if (m.isInScope()) {
                ids.add(m.getItemId());
            }
        }
        return ids;
    }
}
