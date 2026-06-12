package iped.rcp.core.session;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.core.EvidenceStatus;
import iped.engine.data.IPEDSource;

/**
 * Near-live mode commit monitor (task T063, FR-029/FR-030, research R14,
 * data-model {@code CaseSession.commitMonitor}): watches the Lucene index
 * commit generation of every case in the open session and asks
 * {@link CaseSessionService} for an atomic source reload on each new
 * consolidation. Runs only for sessions opened while the case was still
 * being processed ({@link CaseSession#isInteractive()}).
 *
 * <p>
 * Disciplines validated by the T062 spike (research R14 — gate FR-030 met
 * with zero engine changes):
 * <ul>
 * <li>read-only observation only — never takes write locks and never holds
 * index files open between polls (a fresh {@link FSDirectory} handle per
 * generation probe);</li>
 * <li>generation poll every 500 ms is negligible; the actual reload cadence
 * equals the processing {@code commitIntervalSeconds} — short cases may
 * produce a single final consolidation;</li>
 * <li>an index that cannot be read yet (being created/moved) is a normal
 * state, not an error: the probe just retries;</li>
 * <li>the index may live in the processing TEMP folder
 * ({@code indexTempOnSSD}) and move to the case folder at the end — a
 * resolved-path change counts as a new generation;</li>
 * <li>when the on-disk processing status reports completion, one final
 * reload runs and the monitor stops (a crashed processing never reports
 * completion: the monitor keeps idling harmlessly until the session
 * closes).</li>
 * </ul>
 *
 * <p>
 * Functional divergence registered in the parity inventory: items only
 * become visible per consolidation; uncommitted items do not appear (unlike
 * the retired in-process NRT mode of the legacy UI).
 */
class CommitMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommitMonitor.class);

    /** Generation poll period (spike discipline 5). */
    private static final long POLL_INTERVAL_MS = 500;

    /** Processing-status checks are 10x sparser than generation polls. */
    private static final int FINISH_CHECK_RATIO = 10;

    private final CaseSessionService service;
    private final List<CaseWatch> watches = new ArrayList<>();

    private Thread thread;
    private volatile boolean stopped;

    private static final class CaseWatch {
        final File caseDir;
        Path lastIndexPath;
        long lastGeneration = -1;

        CaseWatch(File caseDir) {
            this.caseDir = caseDir;
        }
    }

    CommitMonitor(CaseSessionService service, List<Path> caseDirs) {
        this.service = service;
        for (Path caseDir : caseDirs) {
            watches.add(new CaseWatch(caseDir.toFile()));
        }
    }

    void start() {
        // baseline: the session was just opened over the current state, so
        // the first observation must not trigger a reload
        for (CaseWatch watch : watches) {
            watch.lastIndexPath = resolveIndexDir(watch.caseDir);
            watch.lastGeneration = watch.lastIndexPath == null ? -1 : readGeneration(watch.lastIndexPath);
        }
        thread = new Thread(this::run, "iped-rcp-commit-monitor");
        thread.setDaemon(true);
        thread.start();
        LOGGER.info("Near-live commit monitor started for {} case(s)", watches.size());
    }

    void stop() {
        stopped = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        int polls = 0;
        while (!stopped && service.isOpen()) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                boolean changed = pollGenerations();
                boolean finished = ++polls % FINISH_CHECK_RATIO == 0 && allProcessingFinished();

                if (changed || finished) {
                    boolean reloaded = service.reloadSources();
                    if (finished && reloaded) {
                        // final consolidated view loaded: nothing else to watch
                        LOGGER.info("Processing finished on disk, stopping the near-live commit monitor");
                        return;
                    }
                    if (!reloaded) {
                        LOGGER.warn("Near-live reload skipped/failed, retrying on the next consolidation");
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.error("Commit monitor poll failed", e);
            }
        }
    }

    /** @return true when any watched case has a new commit generation. */
    private boolean pollGenerations() {
        boolean changed = false;
        for (CaseWatch watch : watches) {
            Path indexPath = resolveIndexDir(watch.caseDir);
            if (indexPath == null) {
                continue;
            }
            long generation = readGeneration(indexPath);
            if (generation <= 0) {
                continue;
            }
            boolean moved = !indexPath.equals(watch.lastIndexPath);
            if (moved || generation != watch.lastGeneration) {
                LOGGER.info("New index consolidation detected for {} (generation {}{})", watch.caseDir, generation,
                        moved ? ", index relocated" : "");
                watch.lastIndexPath = indexPath;
                watch.lastGeneration = generation;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Resolves the live index folder the same way {@link IPEDSource} does on
     * open: the case index when present, otherwise the processing temp index
     * advertised by {@code prevTempDir.txt}.
     */
    private static Path resolveIndexDir(File caseDir) {
        File moduleDir = new File(caseDir, IPEDSource.MODULE_DIR);
        File index = new File(moduleDir, "index");
        if (index.isDirectory()) {
            return index.toPath();
        }
        try {
            File tempIndex = IPEDSource.getTempIndexDir(moduleDir);
            return tempIndex.isDirectory() ? tempIndex.toPath() : null;
        } catch (IOException e) {
            // no temp dir info yet: index not created, normal early state
            return null;
        }
    }

    /** Short read-only probe; -1 while the index is unreadable (normal). */
    private static long readGeneration(Path indexPath) {
        try (FSDirectory dir = FSDirectory.open(indexPath)) {
            return SegmentInfos.getLastCommitGeneration(dir);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * On-disk completion check, same source of truth as the interactive-mode
     * detection at session open ({@link EvidenceStatus}).
     */
    private boolean allProcessingFinished() {
        for (CaseWatch watch : watches) {
            try {
                List<String> unfinished = new EvidenceStatus(watch.caseDir).getFailedEvidences();
                if (unfinished == null || !unfinished.isEmpty()) {
                    return false;
                }
            } catch (RuntimeException e) {
                // status file being rewritten by the processing JVM
                return false;
            }
        }
        return true;
    }
}
