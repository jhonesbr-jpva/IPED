package iped.semantic.store;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Chat staging area (research D7, data-model E11), {@code <case>/iped/semantic/staging.mv.db}, on H2
 * MVStore - the same store the engine uses for previews. Messages arrive in any order from the
 * workers and are read back per chat in {@code finish()}, when every neighbour of a window has
 * passed.
 *
 * <p>
 * The file survives an interruption, so {@code --continue} finds the messages of items that will
 * not be processed again, and it is deleted after the sequences are committed. Keys are
 * {@code chatId << 32 | itemId}: a message staged twice (an item reprocessed by {@code --continue})
 * overwrites itself, and a chat's messages are one contiguous key range.
 * </p>
 */
public final class StagingStore implements Closeable {

    private static final String MAP = "messages";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One staged message: its chat, its item, and the literal fields a sequence line shows. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Message {
        public final int chatId;
        public final int itemId;
        /** {@code Communication:Date}, epoch milliseconds; {@code null} when absent. */
        public final Long date;
        /** {@code parentViewPosition}; {@code null} when absent. */
        public final Integer position;
        /** {@code Communication:From}. */
        public final String sender;
        /** {@code Message:Body}, literal. */
        public final String text;

        @JsonCreator
        public Message(@JsonProperty("chatId") int chatId, @JsonProperty("itemId") int itemId,
                @JsonProperty("date") Long date, @JsonProperty("position") Integer position,
                @JsonProperty("sender") String sender, @JsonProperty("text") String text) {
            this.chatId = chatId;
            this.itemId = itemId;
            this.date = date;
            this.position = position;
            this.sender = sender;
            this.text = text == null ? "" : text;
        }
    }

    private final Path file;
    private final MVStore store;
    private final MVMap<Long, String> messages;

    public StagingStore(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        this.file = file;
        this.store = new MVStore.Builder().fileName(file.toString()).open();
        this.messages = store.openMap(MAP);
    }

    private static long key(int chatId, int itemId) {
        return ((long) chatId << 32) | (itemId & 0xFFFFFFFFL);
    }

    public void put(Message m) {
        try {
            messages.put(key(m.chatId, m.itemId), MAPPER.writeValueAsString(m));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Chat ids with staged messages, ascending. */
    public List<Integer> chats() {
        List<Integer> out = new ArrayList<>();
        Long k = messages.isEmpty() ? null : messages.firstKey();
        while (k != null) {
            int chat = (int) (k >>> 32);
            out.add(chat);
            k = messages.ceilingKey(((long) chat + 1) << 32);
        }
        return out;
    }

    /** Messages of one chat, in item id order (the builder sorts by date and position). */
    public List<Message> messagesOf(int chatId) {
        List<Message> out = new ArrayList<>();
        long from = (long) chatId << 32;
        long to = ((long) chatId + 1) << 32;
        Iterator<Long> it = messages.keyIterator(from);
        while (it.hasNext()) {
            Long k = it.next();
            if (k >= to) {
                break;
            }
            out.add(read(messages.get(k)));
        }
        return out;
    }

    private static Message read(String json) {
        try {
            return MAPPER.readValue(json, Message.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int size() {
        return messages.size();
    }

    /** Makes the staged messages durable now (they are also auto-committed about every second). */
    public void commit() {
        store.commit();
    }

    @Override
    public void close() {
        store.close();
    }

    /** Closes and deletes the staging file (after the sequences are committed). */
    public void closeAndDelete() throws IOException {
        store.close();
        Files.deleteIfExists(file);
    }

    /** Diagnostic view of the raw map size per chat. */
    public Map<Long, String> raw() {
        return messages;
    }
}
