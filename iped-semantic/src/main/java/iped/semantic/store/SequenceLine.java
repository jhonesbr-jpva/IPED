package iped.semantic.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One message of a conversation sequence, as displayed: the message's item id, sender, date and its
 * literal {@code Message:Body} (data-model E4). Stored as one JSON object per line.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SequenceLine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int itemId;
    private final String sender;
    private final Long dateMillis;
    private final String text;

    @JsonCreator
    public SequenceLine(@JsonProperty("itemId") int itemId, @JsonProperty("sender") String sender,
            @JsonProperty("date") String date, @JsonProperty("text") String text) {
        this(itemId, sender, date == null ? null : Instant.parse(date).toEpochMilli(), text);
    }

    public SequenceLine(int itemId, String sender, Long dateMillis, String text) {
        this.itemId = itemId;
        this.sender = sender;
        this.dateMillis = dateMillis;
        this.text = text == null ? "" : text;
    }

    @JsonProperty("itemId")
    public int getItemId() {
        return itemId;
    }

    @JsonProperty("sender")
    public String getSender() {
        return sender;
    }

    /** ISO-8601 instant in UTC, or {@code null} when the message has no date. */
    @JsonProperty("date")
    public String getDate() {
        return dateMillis == null ? null : Instant.ofEpochMilli(dateMillis).toString();
    }

    public Long getDateMillis() {
        return dateMillis;
    }

    @JsonProperty("text")
    public String getText() {
        return text;
    }

    /** The line as the model sees it: {@code sender: text} ("who threatens whom" needs the sender). */
    public String embeddingText() {
        return (sender == null || sender.isBlank() ? "" : sender + ": ") + text;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static SequenceLine fromJson(String json) {
        try {
            return MAPPER.readValue(json, SequenceLine.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
