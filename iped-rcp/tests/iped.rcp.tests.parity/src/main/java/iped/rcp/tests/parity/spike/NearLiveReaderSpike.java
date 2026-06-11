package iped.rcp.tests.parity.spike;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.FSDirectory;
import org.sqlite.SQLiteConfig;

/**
 * T062 spike (research R14, gate FR-030): read-only near-live reader running
 * CONCURRENTLY with a real processing of the same case, from a separate JVM.
 *
 * <p>
 * Two legs, mirroring what the analysis UI will do (T063 CommitMonitor):
 * <ul>
 * <li><b>Lucene</b>: polls the index commit generation
 * ({@code SegmentInfos.getLastCommitGeneration}); on each new generation,
 * reopens the reader ({@code DirectoryReader.openIfChanged}) and runs a
 * MatchAllDocs count — validates "reload by index generation" with zero
 * engine change and never takes write locks;</li>
 * <li><b>SQLite storages</b>: read-only connections to
 * {@code storage-*.db} (journal TRUNCATE on the writer side!) issuing short
 * autocommit SELECTs — the SHARED lock window is the contention risk for the
 * writer's COMMIT (precedent: {@code --yara-only} busy-wait with AppMain
 * open).</li>
 * </ul>
 *
 * <p>
 * Usage: {@code NearLiveReaderSpike <caseDir> [maxMinutes]} — stops when
 * {@code <caseDir>/spike-stop.flag} appears, or after maxMinutes (default
 * 240). Appends a CSV-ish log to {@code <caseDir>-spike-reader.log}.
 */
public class NearLiveReaderSpike {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static PrintWriter log;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: NearLiveReaderSpike <caseDir> [maxMinutes]");
            System.exit(2);
        }
        File caseDir = new File(args[0]).getAbsoluteFile();
        long maxMinutes = args.length > 1 ? Long.parseLong(args[1]) : 240;
        File stopFlag = new File(caseDir, "spike-stop.flag");
        File logFile = new File(caseDir.getParentFile(), caseDir.getName() + "-spike-reader.log");
        log = new PrintWriter(Files.newBufferedWriter(logFile.toPath(), StandardCharsets.UTF_8), true);

        long deadline = System.currentTimeMillis() + maxMinutes * 60_000L;
        out("spike-reader started, case=" + caseDir + ", maxMinutes=" + maxMinutes);

        Thread lucene = new Thread(() -> luceneLeg(caseDir, stopFlag, deadline), "spike-lucene");
        Thread sqlite = new Thread(() -> sqliteLeg(caseDir, stopFlag, deadline), "spike-sqlite");
        lucene.start();
        sqlite.start();
        lucene.join();
        sqlite.join();
        out("spike-reader finished");
        log.close();
    }

    // ---------------------------------------------------------------- lucene

    private static void luceneLeg(File caseDir, File stopFlag, long deadline) {
        Path indexPath = caseDir.toPath().resolve("iped").resolve("index");
        try {
            while (keepRunning(stopFlag, deadline) && !Files.isDirectory(indexPath)) {
                Thread.sleep(1000);
            }
            if (!Files.isDirectory(indexPath)) {
                out("lucene: index dir never appeared, leg aborted");
                return;
            }
            out("lucene: index dir found, polling generations");
            try (FSDirectory dir = FSDirectory.open(indexPath)) {
                DirectoryReader reader = null;
                long lastGen = -1;
                int reloads = 0;
                while (keepRunning(stopFlag, deadline)) {
                    long gen;
                    try {
                        gen = SegmentInfos.getLastCommitGeneration(dir);
                    } catch (IOException e) {
                        gen = -1; // index being created/swapped, retry
                    }
                    if (gen > 0 && gen != lastGen) {
                        long t0 = System.nanoTime();
                        try {
                            if (reader == null) {
                                reader = DirectoryReader.open(dir);
                            } else {
                                DirectoryReader changed = DirectoryReader.openIfChanged(reader);
                                if (changed != null) {
                                    reader.close();
                                    reader = changed;
                                }
                            }
                            long openMs = (System.nanoTime() - t0) / 1_000_000;
                            t0 = System.nanoTime();
                            int docs = new IndexSearcher(reader).count(new MatchAllDocsQuery());
                            long countMs = (System.nanoTime() - t0) / 1_000_000;
                            lastGen = gen;
                            reloads++;
                            out(String.format("lucene: gen=%d docs=%d openMs=%d countMs=%d", gen, docs, openMs,
                                    countMs));
                        } catch (IOException e) {
                            out("lucene: reload failed (commit in flight?): " + e);
                        }
                    }
                    Thread.sleep(500);
                }
                if (reader != null) {
                    reader.close();
                }
                out("lucene: leg done, reloads=" + reloads);
            }
        } catch (Exception e) {
            out("lucene: leg crashed: " + e);
            e.printStackTrace(log);
        }
    }

    // ---------------------------------------------------------------- sqlite

    private static void sqliteLeg(File caseDir, File stopFlag, long deadline) {
        File storageDir = new File(caseDir, "iped" + File.separator + "storage");
        Map<File, Connection> connections = new HashMap<>();
        long reads = 0, busy = 0, preCommit = 0, errors = 0, maxMs = 0, totalMs = 0, slowReads = 0, blobBytes = 0;
        try {
            while (keepRunning(stopFlag, deadline)) {
                File[] dbs = storageDir.listFiles((d, n) -> n.startsWith("storage-") && n.endsWith(".db"));
                if (dbs == null || dbs.length == 0) {
                    Thread.sleep(1000);
                    continue;
                }
                for (File db : dbs) {
                    if (!keepRunning(stopFlag, deadline)) {
                        break;
                    }
                    long t0 = System.nanoTime();
                    try {
                        Connection con = connections.computeIfAbsent(db, NearLiveReaderSpike::openReadOnly);
                        // short autocommit reads = brief SHARED lock windows,
                        // the pattern the UI viewers will use (thumbs is what
                        // the ThumbTask writer fills during processing)
                        List<String> ids = new ArrayList<>();
                        try (Statement stmt = con.createStatement();
                                ResultSet rs = stmt.executeQuery("SELECT id FROM thumbs LIMIT 20")) {
                            while (rs.next()) {
                                ids.add(rs.getString(1));
                            }
                        }
                        if (!ids.isEmpty()) {
                            try (PreparedStatement ps = con.prepareStatement("SELECT thumb FROM thumbs WHERE id=?")) {
                                ps.setString(1, ids.get(ids.size() - 1));
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (rs.next()) {
                                        byte[] blob = rs.getBytes(1);
                                        blobBytes += blob == null ? 0 : blob.length;
                                    }
                                }
                            }
                        }
                        long ms = (System.nanoTime() - t0) / 1_000_000;
                        reads++;
                        totalMs += ms;
                        if (ms >= 100) {
                            slowReads++;
                        }
                        if (ms > maxMs) {
                            maxMs = ms;
                            out(String.format("sqlite: new max read latency %d ms on %s", ms, db.getName()));
                        }
                    } catch (SQLException e) {
                        String msg = e.getMessage() == null ? "" : e.getMessage();
                        if (msg.contains("BUSY") || msg.contains("locked")) {
                            busy++;
                        } else if (msg.contains("no such table")) {
                            // expected before the writer's first COMMIT on
                            // this db: DDL is invisible to other processes
                            // until committed (finding for T063)
                            preCommit++;
                        } else {
                            errors++;
                            out("sqlite: read failed on " + db.getName() + ": " + msg);
                            Connection stale = connections.remove(db);
                            closeQuietly(stale);
                        }
                    }
                    Thread.sleep(150);
                }
            }
        } catch (Exception e) {
            out("sqlite: leg crashed: " + e);
            e.printStackTrace(log);
        } finally {
            connections.values().forEach(NearLiveReaderSpike::closeQuietly);
            out(String.format(
                    "sqlite: leg done, reads=%d busy=%d preCommit=%d errors=%d avgMs=%.1f maxMs=%d slowReads=%d blobMB=%.1f",
                    reads, busy, preCommit, errors, reads == 0 ? 0.0 : (double) totalMs / reads, maxMs, slowReads,
                    blobBytes / 1048576.0));
        }
    }

    private static Connection openReadOnly(File db) {
        try {
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            config.setBusyTimeout(10_000);
            return config.createConnection("jdbc:sqlite:" + db.getAbsolutePath());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ----------------------------------------------------------------- utils

    private static boolean keepRunning(File stopFlag, long deadline) {
        return !stopFlag.exists() && System.currentTimeMillis() < deadline;
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // disposing, ignore
            }
        }
    }

    private static synchronized void out(String message) {
        String line = LocalDateTime.now().format(TS) + " " + message;
        System.out.println(line);
        if (log != null) {
            log.println(line);
        }
    }
}
