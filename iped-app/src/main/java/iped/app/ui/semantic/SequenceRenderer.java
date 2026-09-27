package iped.app.ui.semantic;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import iped.semantic.search.DeclarationText;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.OrderingEntry.MessageLine;

/**
 * HTML of a conversation sequence: the messages in order, each with sender and time, exactly as
 * they are ({@code Message-Body}, literal). Messages outside the question's scope are shown as
 * context, dimmed and marked, and are not bookmarked with the entry (research D7).
 */
final class SequenceRenderer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_CHARS_PER_LINE = 400;

    private SequenceRenderer() {
    }

    static String html(OrderingEntry.Sequence seq, DeclarationText text) {
        StringBuilder sb = new StringBuilder();
        for (MessageLine m : seq.getMessages()) {
            String when = m.getDateMillis() == null ? "" : TIME.format(Instant.ofEpochMilli(m.getDateMillis())) + " ";
            String who = m.getSender() == null || m.getSender().isBlank() ? "" : m.getSender() + ": ";
            String body = m.getText();
            if (body.length() > MAX_CHARS_PER_LINE) {
                body = body.substring(0, MAX_CHARS_PER_LINE) + " [...]";
            }
            if (m.isInScope()) {
                sb.append("<div>").append(EntryView.html(when)).append("<b>").append(EntryView.html(who))
                        .append("</b>").append(EntryView.html(body)).append("</div>");
            } else {
                sb.append("<div style='color:gray'>").append(EntryView.html(when + who + body)).append(" <i>(")
                        .append(EntryView.html(text.get("SemanticSearch.OutOfScope"))).append(")</i></div>");
            }
        }
        return sb.toString();
    }
}
