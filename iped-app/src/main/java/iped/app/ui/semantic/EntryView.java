package iped.app.ui.semantic;

import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;

/** An ordering entry as the panel shows it: the entry, its ordering, and the item's name. */
final class EntryView {

    /** What is resolved off the EDT for an entry: the item name and, in the visual modality, its image. */
    static final class Info {
        final String itemName;
        final java.awt.image.BufferedImage image;

        Info(String itemName, java.awt.image.BufferedImage image) {
            this.itemName = itemName;
            this.image = image;
        }
    }

    final OrderingEntry entry;
    final Ordering ordering;
    final String itemName;
    final java.awt.image.BufferedImage image;

    EntryView(OrderingEntry entry, Ordering ordering, Info info) {
        this.entry = entry;
        this.ordering = ordering;
        this.itemName = info == null ? null : info.itemName;
        this.image = info == null ? null : info.image;
    }

    /** Minimal HTML escaping for literal item text inside a Swing HTML label. */
    static String html(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\n':
                    sb.append("<br>");
                    break;
                case '\r':
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
