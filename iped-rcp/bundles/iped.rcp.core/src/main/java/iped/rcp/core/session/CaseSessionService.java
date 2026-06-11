package iped.rcp.core.session;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IIPEDSource;
import iped.engine.config.Configuration;
import iped.engine.core.EvidenceStatus;
import iped.engine.data.Bookmarks;
import iped.engine.data.IPEDMultiSource;
import iped.engine.data.IPEDSource;
import iped.engine.preview.PreviewRepositoryManager;
import iped.rcp.api.ICaseSessionService;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.events.IUiEventPublisher;
import iped.utils.IOUtil;

/**
 * Case session lifecycle service (task T008, data-model "CaseSession"):
 * {@code OPENING -> READY -> CLOSING -> CLOSED} over an
 * {@link IPEDMultiSource}, with single and multicase support (FR-001/FR-002)
 * and read-only media detection.
 *
 * <p>
 * Differences from the legacy Swing loader ({@code UICaseDataLoader}) are
 * deliberate:
 * <ul>
 * <li>cases are always opened with {@code askImagePathIfNotFound=false} — a
 * service must never pop the engine's Swing dialog; missing images surface
 * as a {@link CaseOpenException} for the UI layer to handle;</li>
 * <li>there is no in-process {@code Manager} handshake: the analysis UI is
 * always a separate process from processing (research R14), so the
 * processing-time branches of the legacy loader do not exist here.</li>
 * </ul>
 *
 * <p>
 * Event broker publication ({@code iped/rcp/case/OPENED|CLOSED}) is wired on
 * top of {@link #addSessionListener(Consumer)} by the e4 integration (task
 * T012); this service stays UI-toolkit free so the headless parity harness
 * (T013/T015) can drive it directly.
 */
@Component(service = { ICaseSessionService.class, ICaseSessionManager.class })
public class CaseSessionService implements ICaseSessionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseSessionService.class);

    private final Object lock = new Object();
    private volatile SessionState state = SessionState.CLOSED;
    private volatile CaseSession session;
    private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Optional so the service also runs outside OSGi (parity harness):
     * events degrade to no-ops there (T012).
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.STATIC)
    private IUiEventPublisher eventPublisher;

    @Override
    public CaseSession open(List<Path> casePaths) throws CaseOpenException {
        if (casePaths == null || casePaths.isEmpty()) {
            throw new CaseOpenException("At least one case path is required");
        }
        synchronized (lock) {
            if (state != SessionState.CLOSED) {
                throw new IllegalStateException("A case session is already " + state);
            }
            state = SessionState.OPENING;
        }

        IPEDMultiSource source = null;
        boolean ready = false;
        try {
            loadIpedRootQuietly();

            List<File> caseDirs = resolveCaseDirs(casePaths);
            LOGGER.info("Opening case session with {} case(s)", caseDirs.size());

            appendCaseLibsToJavaClassPath(caseDirs);

            source = openMultiSource(caseDirs);
            try {
                // Same post-open validation as the current UI: fail fast if
                // evidence images referenced by the TSK DB are gone.
                source.checkImagePaths();
            } catch (Exception e) {
                throw new CaseOpenException("Evidence image check failed: " + e.getMessage(), e);
            }
            configurePreviewRepositories(source);

            List<Path> resolvedPaths = new ArrayList<>();
            List<Path> readOnlyPaths = new ArrayList<>();
            boolean interactive = false;
            for (IPEDSource atomic : source.getAtomicSources()) {
                Path caseDir = atomic.getCaseDir().toPath();
                resolvedPaths.add(caseDir);
                if (!isBookmarkAreaWritable(atomic)) {
                    readOnlyPaths.add(caseDir);
                }
                interactive |= isStillProcessing(atomic.getCaseDir());
            }
            if (!readOnlyPaths.isEmpty()) {
                LOGGER.warn("Read-only media detected, bookmark writes will be disabled for: {}", readOnlyPaths);
            }
            if (interactive) {
                LOGGER.info("Case still being processed on disk: session opened in interactive (near-live) mode");
            }

            CaseSession newSession = new CaseSession(resolvedPaths, readOnlyPaths, source, interactive);
            synchronized (lock) {
                session = newSession;
                state = SessionState.READY;
            }
            ready = true;
            LOGGER.info("Case session READY: {}", resolvedPaths);
            notifyListeners(true);
            publish(UiEventTopics.CASE_OPENED, toStrings(resolvedPaths));
            return newSession;

        } catch (RuntimeException e) {
            // Engine constructors throw unchecked ("Index not found",
            // "Image not found", config/IO errors wrapped by IPEDSource).
            throw new CaseOpenException("Error opening case: " + e.getMessage(), e);
        } finally {
            if (!ready) {
                closeQuietly(source);
                synchronized (lock) {
                    session = null;
                    state = SessionState.CLOSED;
                }
            }
        }
    }

    @Override
    public void close() {
        IPEDMultiSource source;
        List<Path> closedPaths;
        synchronized (lock) {
            if (state != SessionState.READY) {
                return;
            }
            state = SessionState.CLOSING;
            source = session.getSource();
            closedPaths = session.getCasePaths();
        }
        LOGGER.info("Closing case session");
        try {
            closeQuietly(source);
        } finally {
            synchronized (lock) {
                session = null;
                state = SessionState.CLOSED;
            }
        }
        notifyListeners(false);
        publish(UiEventTopics.CASE_CLOSED, toStrings(closedPaths));
    }

    @Override
    public CaseSession getSession() {
        return state == SessionState.READY ? session : null;
    }

    @Override
    public SessionState getState() {
        return state;
    }

    @Override
    public boolean isOpen() {
        return state == SessionState.READY;
    }

    @Override
    public List<Path> getCasePaths() {
        CaseSession current = getSession();
        return current != null ? current.getCasePaths() : List.of();
    }

    @Override
    public boolean isInteractive() {
        CaseSession current = getSession();
        return current != null && current.isInteractive();
    }

    @Override
    public Runnable addSessionListener(Consumer<Boolean> openStateListener) {
        listeners.add(openStateListener);
        return () -> listeners.remove(openStateListener);
    }

    private void notifyListeners(boolean open) {
        for (Consumer<Boolean> listener : listeners) {
            try {
                listener.accept(open);
            } catch (RuntimeException e) {
                LOGGER.error("Session listener failed", e);
            }
        }
    }

    private void publish(String topic, Object data) {
        IUiEventPublisher publisher = eventPublisher;
        if (publisher != null) {
            publisher.post(topic, data);
        }
    }

    private static List<String> toStrings(List<Path> paths) {
        return paths.stream().map(Path::toString).toList();
    }

    /**
     * Expands the input paths into concrete case folders (see
     * {@link ICaseSessionManager#open(List)} for the accepted shapes).
     * Discovery of folder trees and txt lists mirrors the private logic of
     * {@link IPEDMultiSource}'s File constructor, which cannot be reused
     * because it would reopen the engine's Swing repath dialog.
     */
    private List<File> resolveCaseDirs(List<Path> casePaths) throws CaseOpenException {
        List<File> caseDirs = new ArrayList<>();
        if (casePaths.size() == 1) {
            File file = casePaths.get(0).toFile().getAbsoluteFile();
            if (!file.exists()) {
                throw new CaseOpenException("Case path not found: " + file);
            }
            if (IPEDSource.checkIfIsCaseFolder(file)) {
                caseDirs.add(file);
            } else if (file.isDirectory()) {
                searchCasesInFolder(file, caseDirs);
                if (caseDirs.isEmpty()) {
                    throw new CaseOpenException("No case folders found under: " + file);
                }
            } else {
                caseDirs.addAll(loadCasesFromTxtFile(file));
            }
        } else {
            for (Path path : casePaths) {
                File file = path.toFile().getAbsoluteFile();
                if (!IPEDSource.checkIfIsCaseFolder(file)) {
                    throw new CaseOpenException("Invalid case path: " + file);
                }
                caseDirs.add(file);
            }
        }
        return caseDirs;
    }

    private void searchCasesInFolder(File folder, List<File> result) {
        File[] subFiles = folder.listFiles();
        if (subFiles == null) {
            return;
        }
        for (File file : subFiles) {
            if (file.isDirectory()) {
                if (IPEDSource.checkIfIsCaseFolder(file)) {
                    result.add(file);
                } else {
                    searchCasesInFolder(file, result);
                }
            }
        }
    }

    private List<File> loadCasesFromTxtFile(File file) throws CaseOpenException {
        List<File> caseDirs = new ArrayList<>();
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            // strip UTF-8 BOM, as the current UI accepts files saved with one
            if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
                bytes[0] = bytes[1] = bytes[2] = ' ';
            }
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                File caseDir = new File(line).getAbsoluteFile();
                if (!IPEDSource.checkIfIsCaseFolder(caseDir)) {
                    throw new CaseOpenException("Invalid case path: " + caseDir);
                }
                caseDirs.add(caseDir);
            }
        } catch (IOException e) {
            throw new CaseOpenException("Error reading multicase list: " + file, e);
        }
        if (caseDirs.isEmpty()) {
            throw new CaseOpenException("No case folders listed in: " + file);
        }
        return caseDirs;
    }

    /**
     * Opens the atomic sources (in parallel for multicase, like the engine
     * does) and wraps them in a single {@link IPEDMultiSource}.
     */
    private IPEDMultiSource openMultiSource(List<File> caseDirs) throws CaseOpenException {
        if (caseDirs.size() == 1) {
            return new IPEDMultiSource(new IPEDSource(caseDirs.get(0), null, false));
        }
        List<IIPEDSource> sources = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try {
            List<Future<IPEDSource>> futures = new ArrayList<>();
            for (File caseDir : caseDirs) {
                futures.add(executor.submit(() -> {
                    LOGGER.info("Loading {}", caseDir.getAbsolutePath());
                    return new IPEDSource(caseDir, null, false);
                }));
            }
            for (Future<IPEDSource> future : futures) {
                sources.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeAllQuietly(sources);
            throw new CaseOpenException("Case opening interrupted", e);
        } catch (ExecutionException e) {
            closeAllQuietly(sources);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new CaseOpenException("Error opening case: " + cause.getMessage(), cause);
        } finally {
            executor.shutdownNow();
        }
        return new IPEDMultiSource(sources);
    }

    /**
     * Configures preview repositories in read-only mode, as the standalone
     * analysis UI does. Failures are not fatal: previews degrade, the case
     * still opens (parity with the current UI behavior).
     */
    private void configurePreviewRepositories(IPEDMultiSource source) {
        for (IPEDSource atomic : source.getAtomicSources()) {
            try {
                PreviewRepositoryManager.configureReadOnly(atomic.getModuleDir());
            } catch (IOException e) {
                LOGGER.error("Error configuring preview repository for {}", atomic.getCaseDir(), e);
            }
        }
    }

    /**
     * Detects read-only media with the same probe the engine uses when
     * deciding where to save bookmarks ({@code BitmapBookmarks.saveState}):
     * the case is writable when the bookmarks state file accepts appends, or
     * does not exist yet but the module dir accepts new files.
     */
    private boolean isBookmarkAreaWritable(IPEDSource atomic) {
        File stateFile = new File(atomic.getModuleDir(), Bookmarks.STATEFILENAME);
        return IOUtil.canWrite(stateFile) || (!stateFile.exists() && IOUtil.canCreateFile(atomic.getModuleDir()));
    }

    /**
     * On-disk heuristic for "case still being processed" (data-model
     * {@code CaseSession.interactive}): the processing status file exists and
     * still lists unfinished evidences. A crashed/failed processing looks the
     * same from disk; the commit monitor (T063) tolerates that — it simply
     * sees no new index generations. To be refined with the T062 spike.
     */
    private boolean isStillProcessing(File caseDir) {
        try {
            List<String> unfinished = new EvidenceStatus(caseDir).getFailedEvidences();
            return unfinished != null && !unfinished.isEmpty();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read processing status of {}", caseDir, e);
            return false;
        }
    }

    /**
     * Makes Java packages importable from the case's Python scripts when
     * running under OSGi. The engine's jep {@code ClassEnquirer}
     * ({@code JEPClassFinder}) discovers Java packages by scanning the
     * {@code java.class.path} property, which under the equinox launcher
     * contains only the launcher jar — so {@code from iped.engine... import}
     * in script tasks raised {@code ModuleNotFoundError}. The self-contained
     * case ships the engine jars in {@code <case>/iped/lib}; appending them
     * to the property lets the enquirer enumerate the packages. This is
     * name enumeration only: classes still load through the engine wrapper
     * bundle (jep resolves them via the caller's class loader).
     *
     * <p>
     * Must run before the first Python interpreter starts (the enquirer is a
     * lazy singleton, initialized during {@code loadConfigurables} of the
     * first case opened).
     */
    private void appendCaseLibsToJavaClassPath(List<File> caseDirs) {
        String pathSeparator = System.getProperty("path.separator");
        StringBuilder classPath = new StringBuilder(System.getProperty("java.class.path", ""));
        int appended = 0;
        for (File caseDir : caseDirs) {
            File[] jars = new File(caseDir, IPEDSource.MODULE_DIR + File.separator + "lib")
                    .listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
            if (jars == null) {
                continue;
            }
            for (File jar : jars) {
                String path = jar.getAbsolutePath();
                if (classPath.indexOf(path) < 0) {
                    classPath.append(pathSeparator).append(path);
                    appended++;
                }
            }
        }
        if (appended > 0) {
            System.setProperty("java.class.path", classPath.toString());
            LOGGER.info("Appended {} case lib jars to java.class.path for the Python class enquirer", appended);
        }
    }

    private void loadIpedRootQuietly() {
        try {
            // resolves the IPED install root used for external tool paths;
            // missing/unreadable is fine outside a full install
            Configuration.getInstance().loadIpedRoot();
        } catch (IOException e) {
            LOGGER.warn("Could not load iped root location", e);
        }
    }

    private void closeQuietly(IPEDMultiSource source) {
        if (source == null) {
            return;
        }
        try {
            source.close();
        } catch (RuntimeException e) {
            LOGGER.error("Error closing case source", e);
        }
    }

    private void closeAllQuietly(List<IIPEDSource> sources) {
        for (IIPEDSource source : sources) {
            try {
                source.close();
            } catch (RuntimeException e) {
                LOGGER.error("Error closing case source", e);
            }
        }
    }
}
