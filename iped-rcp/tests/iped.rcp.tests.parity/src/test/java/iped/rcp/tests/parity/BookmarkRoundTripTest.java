package iped.rcp.tests.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import iped.data.IItemId;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.rcp.api.ItemId;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.CaseSessionService;
import iped.rcp.core.session.ICaseSessionManager;
import iped.search.IMultiSearchResult;

/**
 * Bookmark interoperability round-trip (task T064, SC-009/FR-005).
 *
 * <p>
 * <b>Ida</b>: bookmarks written through the RCP stack ({@code BookmarkService}
 * over the engine's {@code IMultiBookmarks}) and read by the engine on a flat
 * classpath in a SEPARATE plain JVM ({@link BookmarkStateDump}) — the exact
 * code path of the report generator. <b>Volta</b>: bookmarks written by the
 * plain-engine JVM and read back through the RCP stack. The full model is
 * compared: names (with accents), colors, comments, membership (CRC of ids),
 * checked selection. Includes a large bookmark (>= 100k items, exercising the
 * {@code BitmapBookmarks} representation), concurrent writers using the
 * current lock discipline ({@code SaveStateThread}) and an optional multicase
 * leg ({@code -Dcase.dir2}).
 *
 * <p>
 * Classpath note: the OSGi-context leg of SC-009 (service running inside the
 * real product/wrapper bundle) is exercised by the SWTBot flow (T014), which
 * creates a bookmark through the same service inside the launched product;
 * this harness provides the cross-process / cross-classpath comparison, which
 * a single OSGi JVM could not.
 *
 * <p>
 * The reference case is left as found: every bookmark created here carries
 * the {@code T064} prefix and is deleted (with a synchronous state save) at
 * the end.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BookmarkRoundTripTest {

    private static final String PREFIX = "T064 ";
    private static final String IDA_NAME = PREFIX + "ida àçãé éção";
    private static final String IDA_BIG_NAME = PREFIX + "ida-bitmap-100k";
    private static final String VOLTA_NAME = PREFIX + "volta nãção";
    private static final String CONC_NAME_A = PREFIX + "concorrente-A";
    private static final String CONC_NAME_B = PREFIX + "concorrente-B";

    private static final int IDA_RGB = 0x1A2B3C;
    private static final int VOLTA_RGB = 0xCC0044;

    private static ICaseSessionManager manager;
    private static CaseSession session;
    private static BookmarkService bookmarkService;
    private static SearchService searchService;
    private static String caseDir;

    @BeforeAll
    static void openCase() throws Exception {
        caseDir = System.getProperty("case.dir");
        assumeTrue(caseDir != null && !caseDir.isBlank(), "-Dcase.dir not set, skipping bookmark round-trip tests");
        manager = new CaseSessionService();
        session = manager.open(List.of(Path.of(caseDir)));
        bookmarkService = new BookmarkService(manager);
        searchService = new SearchService(manager);
    }

    @AfterAll
    static void cleanup() {
        if (session == null) {
            return;
        }
        try {
            for (String name : List.of(IDA_NAME, IDA_BIG_NAME, VOLTA_NAME, CONC_NAME_A, CONC_NAME_B)) {
                if (bookmarkService.getBookmarkNames().contains(name)) {
                    bookmarkService.deleteBookmark(name);
                }
            }
            // also drop the checked marks created by the legs above
            session.getSource().getMultiBookmarks().clearChecked();
            session.getSource().getMultiBookmarks().saveState(true);
        } finally {
            manager.close();
        }
    }

    @Test
    @Order(1)
    void idaRcpWritePlainEngineRead() throws Exception {
        List<ItemId> all = searchService.search("*", 0, Integer.MAX_VALUE);
        assertTrue(all.size() > 1, "reference case must have items");

        // regular bookmark with accents, color, comment and checked items
        List<ItemId> members = all.subList(0, Math.min(500, all.size()));
        bookmarkService.createBookmark(IDA_NAME);
        bookmarkService.addToBookmark(IDA_NAME, members);
        bookmarkService.setColor(IDA_NAME, IDA_RGB);
        bookmarkService.setComment(IDA_NAME, "comentário com acentuação");
        for (ItemId item : members.subList(0, Math.min(10, members.size()))) {
            bookmarkService.setChecked(item, true);
        }

        // large bookmark: >= 100k members exercises BitmapBookmarks
        int bigSize = Math.min(100_000, all.size());
        bookmarkService.createBookmark(IDA_BIG_NAME);
        bookmarkService.addToBookmark(IDA_BIG_NAME, all.subList(0, bigSize));

        session.getSource().getMultiBookmarks().saveState(true);

        // read in a separate flat-classpath JVM (report generator path)
        List<String> dump = runChild("dump", PREFIX + "ida", caseDir);
        assertEquals(expectedLine(IDA_NAME), findLine(dump, IDA_NAME),
                "plain engine JVM must read back the RCP-written bookmark identically");
        String bigLine = findLine(dump, IDA_BIG_NAME);
        assertEquals(bigSize, Integer.parseInt(bigLine.split("\\|")[2]),
                "large (bitmap) bookmark count must survive the round-trip");
    }

    @Test
    @Order(2)
    void voltaPlainEngineWriteRcpRead() throws Exception {
        // checked state is case-global (not per bookmark): reset it so the
        // count below isolates the child JVM's writes from the ida leg
        session.getSource().getMultiBookmarks().clearChecked();
        session.getSource().getMultiBookmarks().saveState(true);

        int nItems = 200;
        List<String> output = runChild("write", VOLTA_NAME, Integer.toString(VOLTA_RGB),
                "comentário plano", Integer.toString(nItems), caseDir);
        assertTrue(output.contains("WRITE_OK"), "plain engine writer must succeed: " + output);

        // reload the on-disk state written by the other process
        session.getSource().getMultiBookmarks().loadState();

        assertTrue(bookmarkService.getBookmarkNames().contains(VOLTA_NAME),
                "RCP stack must list the bookmark written by the plain engine");
        assertEquals(nItems, bookmarkService.getBookmarkItems(VOLTA_NAME).size());
        assertEquals(Optional.of(VOLTA_RGB), bookmarkService.getColor(VOLTA_NAME));
        assertEquals(Optional.of("comentário plano"), bookmarkService.getComment(VOLTA_NAME));
        long checked = bookmarkService.getBookmarkItems(VOLTA_NAME).stream()
                .filter(bookmarkService::isChecked).count();
        assertEquals(10, checked, "checked selection must survive the round-trip");
    }

    @Test
    @Order(3)
    void concurrentWritersFollowSaveStateDiscipline() throws Exception {
        List<ItemId> all = searchService.search("*", 0, 2_000);
        List<ItemId> firstHalf = all.subList(0, all.size() / 2);
        List<ItemId> secondHalf = all.subList(all.size() / 2, all.size());

        bookmarkService.createBookmark(CONC_NAME_A);
        bookmarkService.createBookmark(CONC_NAME_B);

        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        Thread writerA = writer(start, failures, CONC_NAME_A, firstHalf);
        Thread writerB = writer(start, failures, CONC_NAME_B, secondHalf);
        writerA.start();
        writerB.start();
        start.countDown();
        writerA.join(TimeUnit.MINUTES.toMillis(2));
        writerB.join(TimeUnit.MINUTES.toMillis(2));
        assertTrue(failures.isEmpty(), "concurrent bookmark writers must not fail: " + failures);

        // current discipline: async saves queued on SaveStateThread, final
        // synchronous flush wins
        session.getSource().getMultiBookmarks().saveState(true);

        List<String> dump = runChild("dump", PREFIX + "concorrente", caseDir);
        assertEquals(firstHalf.size(), Integer.parseInt(findLine(dump, CONC_NAME_A).split("\\|")[2]));
        assertEquals(secondHalf.size(), Integer.parseInt(findLine(dump, CONC_NAME_B).split("\\|")[2]));
    }

    @Test
    @Order(4)
    void multicaseRoundTrip() throws Exception {
        String caseDir2 = System.getProperty("case.dir2");
        assumeTrue(caseDir2 != null && !caseDir2.isBlank(), "-Dcase.dir2 not set, skipping multicase leg");

        ICaseSessionManager multiManager = new CaseSessionService();
        CaseSession multiSession = multiManager.open(List.of(Path.of(caseDir), Path.of(caseDir2)));
        try {
            BookmarkService multiBookmarks = new BookmarkService(multiManager);
            SearchService multiSearch = new SearchService(multiManager);
            String name = PREFIX + "multicase";
            List<ItemId> members = multiSearch.search("*", 0, 300);
            assertTrue(members.stream().mapToInt(ItemId::sourceId).distinct().count() >= 1);
            try {
                multiBookmarks.createBookmark(name);
                multiBookmarks.addToBookmark(name, members);
                multiSession.getSource().getMultiBookmarks().saveState(true);

                List<String> dump = runChild("dump", name, caseDir, caseDir2);
                assertEquals(members.size(), Integer.parseInt(findLine(dump, name).split("\\|")[2]),
                        "multicase bookmark must be readable by the plain engine over the same case set");
            } finally {
                multiBookmarks.deleteBookmark(name);
                multiSession.getSource().getMultiBookmarks().saveState(true);
            }
        } finally {
            multiManager.close();
        }
    }

    private static Thread writer(CountDownLatch start, List<Throwable> failures, String name, List<ItemId> items) {
        return new Thread(() -> {
            try {
                start.await();
                int batch = 200;
                for (int i = 0; i < items.size(); i += batch) {
                    bookmarkService.addToBookmark(name, items.subList(i, Math.min(items.size(), i + batch)));
                }
            } catch (Throwable t) {
                synchronized (failures) {
                    failures.add(t);
                }
            }
        }, "t064-writer-" + name);
    }

    /** Canonical line the plain dump must produce for the "ida" bookmark. */
    private String expectedLine(String name) throws Exception {
        IMultiSearchResult members = membersOf(name);
        int checked = 0;
        for (IItemId member : members.getIterator()) {
            if (session.getSource().getMultiBookmarks().isChecked(member)) {
                checked++;
            }
        }
        return "BOOKMARK|" + name + "|" + session.getSource().getMultiBookmarks().getBookmarkCount(name) + "|"
                + Integer.toString(IDA_RGB, 16) + "|comentário com acentuação|" + checked + "|"
                + BookmarkStateDump.membershipCrc(members);
    }

    private IMultiSearchResult membersOf(String name) throws Exception {
        MultiSearchResult all = new IPEDSearcher(session.getSource(), "*").multiSearch();
        return session.getSource().getMultiBookmarks().filterBookmarks(all, java.util.Set.of(name));
    }

    private static String findLine(List<String> dump, String name) {
        return dump.stream().filter(l -> l.startsWith("BOOKMARK|" + name + "|")).findFirst()
                .orElseThrow(() -> new AssertionError("bookmark '" + name + "' missing in child dump: " + dump));
    }

    /** Launches {@link BookmarkStateDump} in a plain JVM with this JVM's classpath. */
    private static List<String> runChild(String... args) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>(List.of(javaBin, "-Xmx2g",
                "-Djava.security.manager=allow",
                "-Dfile.encoding=UTF-8",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.math=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                BookmarkStateDump.class.getName()));
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // surefire classpaths overflow the Windows command line (error=206);
        // the CLASSPATH environment variable has no such limit
        pb.environment().put("CLASSPATH", System.getProperty("java.class.path"));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        int exit = process.waitFor();
        assertEquals(0, exit, "child engine JVM failed (exit " + exit + "): " + String.join("\n", lines));
        return lines;
    }
}
