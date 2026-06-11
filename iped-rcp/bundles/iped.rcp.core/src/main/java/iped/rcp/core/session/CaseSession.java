package iped.rcp.core.session;

import java.nio.file.Path;
import java.util.List;

import iped.engine.data.IPEDMultiSource;

/**
 * An open analysis session over one case or a multicase set (data-model
 * "CaseSession"). Evidence data is strictly read-only (FR-004): the only
 * writes allowed downstream are bookmarks (current format, FR-005), saved
 * filters and user UI preferences.
 *
 * <p>
 * Instances are created by {@link CaseSessionService} when the session
 * reaches {@link SessionState#READY} and must not be used after the session
 * is closed (the underlying index readers are disposed).
 */
public final class CaseSession {

    private final List<Path> casePaths;
    private final List<Path> readOnlyCasePaths;
    private final IPEDMultiSource source;
    private final boolean interactive;

    CaseSession(List<Path> casePaths, List<Path> readOnlyCasePaths, IPEDMultiSource source, boolean interactive) {
        this.casePaths = List.copyOf(casePaths);
        this.readOnlyCasePaths = List.copyOf(readOnlyCasePaths);
        this.source = source;
        this.interactive = interactive;
    }

    /**
     * @return the resolved case folders of this session: one entry for a
     *         single case, one per atomic case for a multicase session
     *         (FR-001/FR-002)
     */
    public List<Path> getCasePaths() {
        return casePaths;
    }

    /**
     * Case folders living on media that rejects writes (e.g. optical/locked
     * disks). Bookmark saving falls back to the engine's local cookie file
     * there; the UI must warn the user (edge case in the spec).
     */
    public List<Path> getReadOnlyCasePaths() {
        return readOnlyCasePaths;
    }

    /** @return true when at least one case folder is on read-only media */
    public boolean isReadOnlyMedia() {
        return !readOnlyCasePaths.isEmpty();
    }

    /**
     * The engine source backing this session. Single cases are wrapped in an
     * {@link IPEDMultiSource} of one atomic source, so consumers handle both
     * shapes uniformly.
     */
    public IPEDMultiSource getSource() {
        return source;
    }

    /**
     * True when the case was still being processed when this session was
     * opened, as detected from the on-disk processing status. Enables the
     * near-live refresh mode (FR-029): the commit monitor (task T063, gated
     * by the T062 contention spike) watches index generations and reloads
     * the read-only reader on each consolidation.
     */
    public boolean isInteractive() {
        return interactive;
    }
}
