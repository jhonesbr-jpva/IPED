package iped.rcp.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.osgi.service.datalocation.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-user, per-case workspace area resolver (task T043, FR-017, research R5,
 * data-model {@code WorkspaceState}).
 *
 * <p>
 * The e4 persisted state ({@code workbench.xmi} — part arrangement, sash
 * weights, window geometry) lives in the OSGi instance area. This resolver
 * points that area at {@code ~/.iped/ui-workspaces/<case-id>/}, where
 * {@code case-id} is derived from the canonical case path(s): layout never
 * travels with the case (read-only media safe, forensic artifact untouched —
 * Principle IV) and each case keeps its own arrangement.
 *
 * <p>
 * The location is set from the e4 lifecycle, before the application model is
 * loaded (the standard e4 workspace-chooser pattern). An explicit {@code -data}
 * argument (tests, tycho-surefire harness, power users) wins: when the
 * location is already set, only the state validation runs against it.
 *
 * <p>
 * State hygiene (silent reset, never a boot failure): a corrupted
 * {@code workbench.xmi} or a layout-version stamp different from
 * {@link #LAYOUT_VERSION} moves the state aside ({@code .bak}) so the
 * workbench boots the default layout from {@code Application.e4xmi} —
 * equivalent to {@code -clearPersistedState} for that area only. Bump
 * {@link #LAYOUT_VERSION} whenever {@code Application.e4xmi} changes
 * incompatibly (persisted models always win over the static model, so new
 * static elements would otherwise never reach existing workspaces).
 */
public final class WorkspaceLocationResolver {

    /** Override of the workspace root folder (tests/harness). */
    public static final String WORKSPACE_ROOT_PROP = "iped.rcp.workspace.root";

    /**
     * Generation of the static application model. Bump on incompatible
     * {@code Application.e4xmi} changes; existing workspaces reset silently.
     * Version 2: main menu + commands/keybindings (T044/T046).
     * Version 3: top toolbar with New/Open Case items.
     * Version 4: toolbar items switched to icons (iconURI).
     * Version 5: content viewer split into separate Preview/Text/Metadata/Hex
     * top-level parts (replaces the single combined Viewer part).
     */
    public static final String LAYOUT_VERSION = "5";

    /** Outcome of {@link #validateOrResetState(Path)}. */
    public enum StateCheck {
        /** No persisted state yet (first launch on this area). */
        FRESH,
        /** Persisted state present and usable. */
        COMPATIBLE,
        /** workbench.xmi was unreadable and has been moved aside. */
        RESET_CORRUPTED,
        /** State written by another layout generation; moved aside. */
        RESET_INCOMPATIBLE
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceLocationResolver.class);

    private static final String DEFAULT_ROOT = ".iped/ui-workspaces";
    private static final String WORKBENCH_XMI = ".metadata/.plugins/org.eclipse.e4.workbench/workbench.xmi";
    private static final String VERSION_STAMP = ".metadata/.plugins/iped.rcp.app/layout.version";
    private static final int CASE_ID_HASH_CHARS = 16;
    private static final int CASE_ID_NAME_CHARS = 24;

    private WorkspaceLocationResolver() {
    }

    /**
     * Resolves (and points, when not externally set) the instance area to the
     * per-case workspace, running state validation either way.
     *
     * @return {@code true} when the per-case area was applied; {@code false}
     *         when an explicit {@code -data} was respected or the location
     *         service is unavailable (boot proceeds on the framework default)
     */
    public static boolean applyTo(IEclipseContext context, List<Path> casePaths) {
        // E4Workbench.INSTANCE_LOCATION (internal constant, value verified
        // against the target platform): E4Application publishes the OSGi
        // instance Location here before invoking the lifecycle
        Location location = (Location) context.get("instanceLocation");
        if (location == null) {
            LOGGER.warn("Instance location service not found; default workspace area kept");
            return false;
        }
        if (location.isSet()) {
            // explicit -data (harness/tests/power user): respect, validate
            File current = areaFile(location);
            if (current != null) {
                StateCheck check = validateOrResetState(current.toPath());
                LOGGER.info("Explicit -data {} kept (state: {})", current, check);
            }
            return false;
        }
        try {
            Path workspace = resolveWorkspaceDir(casePaths);
            Files.createDirectories(workspace);
            StateCheck check = validateOrResetState(workspace);
            if (!location.set(workspace.toUri().toURL(), false)) {
                LOGGER.warn("Instance location rejected {}; default workspace area kept", workspace);
                return false;
            }
            LOGGER.info("Workspace area: {} (state: {})", workspace, check);
            return true;
        } catch (Exception e) {
            // never fail the boot over layout state — worst case the default
            // area is used and the layout starts fresh
            LOGGER.error("Could not apply per-case workspace area; using default", e);
            return false;
        }
    }

    /**
     * Validates the persisted state of the framework default instance area
     * (the menu-driven, no-case boot uses {@code osgi.instance.area.default}
     * rather than a per-case area, so {@link #applyTo} does not run). This keeps
     * the layout-version reset symmetric: an incompatible
     * {@code Application.e4xmi} change resets the stale {@code workbench.xmi}
     * here too. Never sets the location (the product default stands); only
     * validates the already-resolved area URL.
     *
     * @return the state outcome, or {@code null} when the area cannot be
     *         resolved (boot proceeds untouched)
     */
    public static StateCheck validateDefaultArea(IEclipseContext context) {
        Location location = (Location) context.get("instanceLocation");
        if (location == null) {
            return null;
        }
        File area = areaFile(location);
        if (area == null) {
            return null;
        }
        StateCheck check = validateOrResetState(area.toPath());
        LOGGER.info("Default workspace area: {} (state: {})", area, check);
        return check;
    }

    /**
     * The per-case workspace folder: {@code <root>/<name>-<hash>} where
     * {@code root} is {@code ~/.iped/ui-workspaces} (overridable via
     * {@link #WORKSPACE_ROOT_PROP}), {@code name} is the sanitized first case
     * folder name (plus {@code +N} for multicase) and {@code hash} the first
     * {@value #CASE_ID_HASH_CHARS} hex chars of the SHA-256 over the sorted
     * canonical case paths — stable, order-insensitive and collision-safe.
     */
    public static Path resolveWorkspaceDir(List<Path> casePaths) {
        if (casePaths == null || casePaths.isEmpty()) {
            throw new IllegalArgumentException("at least one case path is required");
        }
        // the whole id (hash AND readable prefix) must be order-insensitive:
        // sort the paths by their canonical key first
        List<Path> ordered = new ArrayList<>(casePaths);
        ordered.sort(java.util.Comparator.comparing(WorkspaceLocationResolver::canonicalKey));
        List<String> canonical = new ArrayList<>(ordered.size());
        for (Path casePath : ordered) {
            canonical.add(canonicalKey(casePath));
        }

        String hash = sha256Hex(String.join("\n", canonical)).substring(0, CASE_ID_HASH_CHARS);
        Path first = ordered.get(0);
        String name = sanitize(first.getFileName() != null ? first.getFileName().toString() : first.toString());
        if (ordered.size() > 1) {
            name += "+" + (ordered.size() - 1);
        }
        return workspaceRoot().resolve(name + "-" + hash);
    }

    /** The {@code workbench.xmi} location inside a workspace area. */
    public static Path workbenchXmi(Path workspaceDir) {
        return workspaceDir.resolve(WORKBENCH_XMI);
    }

    /** The layout-version stamp location inside a workspace area. */
    public static Path layoutVersionStamp(Path workspaceDir) {
        return workspaceDir.resolve(VERSION_STAMP);
    }

    /**
     * Validates the persisted state of a workspace area, resetting it (move
     * aside + restamp) when corrupted or written by another layout
     * generation. Always leaves the area stamped with {@link #LAYOUT_VERSION}.
     * Never throws: I/O hiccups degrade to a best-effort reset.
     */
    public static StateCheck validateOrResetState(Path workspaceDir) {
        Path xmi = workbenchXmi(workspaceDir);
        Path stamp = layoutVersionStamp(workspaceDir);
        try {
            if (!Files.exists(xmi)) {
                // no persisted state: FRESH only the first time on this
                // area — once stamped with the current generation, later
                // boots without state are plain compatible boots
                String version = Files.exists(stamp) ? Files.readString(stamp, StandardCharsets.UTF_8).trim()
                        : null;
                if (LAYOUT_VERSION.equals(version)) {
                    return StateCheck.COMPATIBLE;
                }
                writeStamp(stamp);
                return StateCheck.FRESH;
            }
            String version = Files.exists(stamp) ? Files.readString(stamp, StandardCharsets.UTF_8).trim() : null;
            if (!LAYOUT_VERSION.equals(version)) {
                LOGGER.warn("Workspace layout generation '{}' != '{}'; resetting to the default layout",
                        version, LAYOUT_VERSION);
                moveAside(xmi);
                writeStamp(stamp);
                return StateCheck.RESET_INCOMPATIBLE;
            }
            if (!isWellFormedXml(xmi)) {
                LOGGER.warn("Corrupted workbench.xmi at {}; resetting to the default layout", xmi);
                moveAside(xmi);
                writeStamp(stamp);
                return StateCheck.RESET_CORRUPTED;
            }
            return StateCheck.COMPATIBLE;
        } catch (IOException e) {
            LOGGER.warn("Workspace state validation failed; resetting to the default layout", e);
            try {
                moveAside(xmi);
                writeStamp(stamp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
                LOGGER.error("Workspace state reset also failed; boot proceeds", e);
            }
            return StateCheck.RESET_CORRUPTED;
        }
    }

    // ------------------------------------------------------------------

    private static Path workspaceRoot() {
        String override = System.getProperty(WORKSPACE_ROOT_PROP);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home")).resolve(DEFAULT_ROOT);
    }

    private static String canonicalKey(Path casePath) {
        Path normalized;
        try {
            // resolves links and on-disk name casing when the case exists
            normalized = casePath.toRealPath();
        } catch (IOException e) {
            normalized = casePath.toAbsolutePath().normalize();
        }
        String key = normalized.toString();
        // Windows paths are case-insensitive (H:\ vs h:\ must not fork state)
        return File.separatorChar == '\\' ? key.toLowerCase(java.util.Locale.ROOT) : key;
    }

    private static String sanitize(String name) {
        StringBuilder safe = new StringBuilder(Math.min(name.length(), CASE_ID_NAME_CHARS));
        for (int i = 0; i < name.length() && safe.length() < CASE_ID_NAME_CHARS; i++) {
            char c = name.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return safe.isEmpty() ? "case" : safe.toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isWellFormedXml(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            try (InputStream in = Files.newInputStream(file)) {
                factory.newDocumentBuilder().parse(in);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void moveAside(Path xmi) throws IOException {
        if (Files.exists(xmi)) {
            Files.move(xmi, xmi.resolveSibling(xmi.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeStamp(Path stamp) throws IOException {
        Files.createDirectories(stamp.getParent());
        Files.writeString(stamp, LAYOUT_VERSION, StandardCharsets.UTF_8);
    }

    private static File areaFile(Location location) {
        URL url = location.getURL();
        if (url == null || !"file".equals(url.getProtocol())) {
            // No-case (menu-driven) boot: the instance Location is not locked yet
            // at @PostContextCreate, so getURL() is null. Fall back to the
            // configured default (osgi.instance.area.default, @user.home already
            // resolved) so the layout-version reset (validateDefaultArea) also
            // runs for the default workspace — otherwise an incompatible
            // Application.e4xmi change leaves the stale workbench.xmi in place and
            // a removed part fails to load (blank tab).
            url = location.getDefault();
        }
        if (url == null || !"file".equals(url.getProtocol())) {
            return null;
        }
        String path = url.getPath();
        if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
            path = path.substring(1); // file:/C:/... on Windows
        }
        return new File(path);
    }
}
