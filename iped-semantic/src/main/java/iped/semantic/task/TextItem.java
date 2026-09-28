package iped.semantic.task;

import java.io.IOException;
import java.io.Reader;
import java.util.Set;

/**
 * What the text preparation needs from an item, independent of {@code IItem}: the task builds it
 * from the processing item, tests build it directly.
 */
public final class TextItem {

    /** Opens the item's extracted text; {@code null} when the item has none. */
    public interface TextSource {
        Reader open() throws IOException;
    }

    final int id;
    final String mediaType;
    final Set<String> categories;
    final TextSource text;
    final String transcript;
    /** Message fields; {@code chatId == null} for items that are not chat messages. */
    final Integer chatId;
    final Long date;
    final Integer position;
    final String sender;
    final String body;
    /** The text was drawn from a binary by strings extraction ({@link #binaryText()}). */
    boolean binaryText;
    /**
     * Parent item of a document ({@link #parent(Integer)}): the chat of a message-type item that has
     * no body.
     */
    Integer parentId;

    private TextItem(int id, String mediaType, Set<String> categories, TextSource text, String transcript,
            Integer chatId, Long date, Integer position, String sender, String body) {
        this.id = id;
        this.mediaType = mediaType == null ? "" : mediaType;
        this.categories = categories == null ? Set.of() : categories;
        this.text = text;
        this.transcript = transcript;
        this.chatId = chatId;
        this.date = date;
        this.position = position;
        this.sender = sender;
        this.body = body;
    }

    /** An ordinary item: a document, e-mail, image with OCR, audio with transcript... */
    public static TextItem document(int id, String mediaType, Set<String> categories, TextSource text,
            String transcript) {
        return new TextItem(id, mediaType, categories, text, transcript, null, null, null, null, null);
    }

    /**
     * A chat message: the message fields, plus its document view, used when its media type is not
     * one of {@code messageMediaTypes}.
     */
    public static TextItem message(int id, String mediaType, Set<String> categories, TextSource text,
            String transcript, int chatId, Long date, Integer position, String sender, String body) {
        return new TextItem(id, mediaType, categories, text, transcript, chatId, date, position, sender, body);
    }

    /**
     * The same item as a document whose text fails to open, as a parser failure would. Only the
     * processing test hook of {@link SemanticTextTask} builds one.
     */
    TextItem failing(String reason) {
        TextSource broken = () -> {
            throw new IOException(reason);
        };
        return new TextItem(id, mediaType, categories, broken, null, null, null, null, null, null);
    }

    /**
     * Marks the item's text as drawn from a binary by strings extraction; such text is prepared
     * only with {@code prepareBinaryText} on. Returns this item.
     */
    public TextItem binaryText() {
        this.binaryText = true;
        return this;
    }

    /**
     * Records the item's parent. A message-type item with no body is staged under it with no text,
     * so its chat is known to hold messages none of which has text. Returns this item.
     */
    public TextItem parent(Integer parentId) {
        this.parentId = parentId;
        return this;
    }

    public boolean isMessage() {
        return chatId != null;
    }

    public int getId() {
        return id;
    }
}
