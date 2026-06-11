package iped.rcp.tests.parity;

import java.awt.Color;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32;

import iped.data.IItemId;
import iped.data.IMultiBookmarks;
import iped.engine.data.IPEDMultiSource;
import iped.engine.data.IPEDSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.search.IMultiSearchResult;

/**
 * Plain-classpath child process of {@link BookmarkRoundTripTest} (task T064).
 * Runs the SAME engine code paths as the report generator and the current UI
 * (flat classpath, no OSGi): reads or writes bookmarks of a case and prints a
 * canonical dump for cross-process comparison.
 *
 * <p>
 * Usage:
 * <ul>
 * <li>{@code dump <prefix> <caseDir> [caseDir2...]} - prints one
 * {@code BOOKMARK|name|count|rgb|comment|checked|membershipCrc} line per
 * bookmark whose name starts with the prefix (sorted by name);</li>
 * <li>{@code write <name> <rgb> <comment> <nItems> <caseDir> [caseDir2...]} -
 * creates the bookmark over the first {@code nItems} items (index order),
 * sets color and comment, checks the first 10 members and saves
 * synchronously.</li>
 * </ul>
 */
public final class BookmarkStateDump {

    private BookmarkStateDump() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if ("dump".equals(mode)) {
            try (CaseHandle handle = open(slice(args, 2))) {
                dump(out, handle.source, args[1]);
            }
        } else if ("write".equals(mode)) {
            try (CaseHandle handle = open(slice(args, 5))) {
                write(handle.source, args[1], Integer.parseInt(args[2]), args[3], Integer.parseInt(args[4]));
            }
            out.println("WRITE_OK");
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    private static String[] slice(String[] args, int from) {
        String[] result = new String[args.length - from];
        System.arraycopy(args, from, result, 0, result.length);
        return result;
    }

    private static CaseHandle open(String[] caseDirs) {
        List<iped.data.IIPEDSource> sources = new ArrayList<>();
        for (String dir : caseDirs) {
            sources.add(new IPEDSource(new File(dir), null, false));
        }
        return new CaseHandle(new IPEDMultiSource(sources));
    }

    private static void dump(PrintStream out, IPEDMultiSource source, String prefix) throws Exception {
        IMultiBookmarks bookmarks = source.getMultiBookmarks();
        MultiSearchResult all = new IPEDSearcher(source, "*").multiSearch();
        for (String name : new TreeSet<>(bookmarks.getBookmarkSet())) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            IMultiSearchResult members = bookmarks.filterBookmarks(all, Set.of(name));
            Color color = bookmarks.getBookmarkColor(name);
            String comment = bookmarks.getBookmarkComment(name);
            int checked = 0;
            for (IItemId member : members.getIterator()) {
                if (bookmarks.isChecked(member)) {
                    checked++;
                }
            }
            out.println("BOOKMARK|" + name + "|" + bookmarks.getBookmarkCount(name) + "|"
                    + (color == null ? "-" : Integer.toString(color.getRGB() & 0xFFFFFF, 16)) + "|"
                    + (comment == null || comment.isEmpty() ? "-" : comment) + "|" + checked + "|"
                    + membershipCrc(members));
        }
        out.println("DUMP_OK");
    }

    private static void write(IPEDMultiSource source, String name, int rgb, String comment, int nItems)
            throws Exception {
        IMultiBookmarks bookmarks = source.getMultiBookmarks();
        MultiSearchResult all = new IPEDSearcher(source, "*").multiSearch();
        Set<IItemId> members = new HashSet<>();
        for (IItemId item : all.getIterator()) {
            if (members.size() >= nItems) {
                break;
            }
            members.add(item);
        }
        bookmarks.newBookmark(name);
        bookmarks.addBookmark(members, name);
        bookmarks.setBookmarkColor(name, new Color(rgb));
        bookmarks.setBookmarkComment(name, comment);
        int checked = 0;
        for (IItemId item : members) {
            if (checked++ >= 10) {
                break;
            }
            bookmarks.setChecked(true, item);
        }
        bookmarks.saveState(true);
    }

    /**
     * Order-independent CRC of the (sourceId, id) membership, comparable
     * across processes and across the RCP/plain readers.
     */
    static long membershipCrc(IMultiSearchResult members) {
        TreeSet<Long> sorted = new TreeSet<>();
        for (IItemId member : members.getIterator()) {
            sorted.add(((long) member.getSourceId() << 32) | (member.getId() & 0xFFFFFFFFL));
        }
        CRC32 crc = new CRC32();
        for (long key : sorted) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                crc.update((int) (key >>> shift) & 0xFF);
            }
        }
        return crc.getValue();
    }

    private record CaseHandle(IPEDMultiSource source) implements AutoCloseable {
        @Override
        public void close() {
            source.close();
        }
    }
}
