package iped.rcp.app.theme;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Theme preference of the analysis UI (task T044, FR-018, research R8).
 *
 * <p>
 * Stored per user in {@code ~/.iped/UiTheme.txt} — same area and style as the
 * legacy {@code UiScale.txt}: the theme is a user choice that applies to every
 * case, so it deliberately does NOT live in the per-case workspace area
 * (data-model {@code WorkspaceState} boundary: presentation-only, user scope).
 * The {@code iped.rcp.theme} system property overrides the file (tests).
 */
public final class ThemePreferences {

    /** Theme selection modes (FR-018): follow the OS, or force light/dark. */
    public enum Mode {
        SYSTEM, LIGHT, DARK;

        static Mode parse(String value, Mode fallback) {
            if (value != null) {
                try {
                    return valueOf(value.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // unknown value in the file: fall back to the default
                }
            }
            return fallback;
        }
    }

    /** System property override (tests/diagnostics). */
    public static final String THEME_PROP = "iped.rcp.theme";

    private static final Logger LOGGER = LoggerFactory.getLogger(ThemePreferences.class);
    private static final String KEY = "theme";

    private ThemePreferences() {
    }

    private static File preferenceFile() {
        return new File(System.getProperty("user.home") + "/.iped", "UiTheme.txt");
    }

    /** The configured mode; defaults to {@link Mode#SYSTEM} (native, R8). */
    public static Mode load() {
        String override = System.getProperty(THEME_PROP);
        if (override != null && !override.isBlank()) {
            return Mode.parse(override, Mode.SYSTEM);
        }
        File file = preferenceFile();
        if (file.isFile()) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file.toPath())) {
                properties.load(in);
                return Mode.parse(properties.getProperty(KEY), Mode.SYSTEM);
            } catch (IOException e) {
                LOGGER.warn("Could not read {}; using system theme", file, e);
            }
        }
        return Mode.SYSTEM;
    }

    /** Persists the mode (best effort; the preference is presentation-only). */
    public static void save(Mode mode) {
        File file = preferenceFile();
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            Properties properties = new Properties();
            properties.setProperty(KEY, mode.name().toLowerCase(Locale.ROOT));
            try (OutputStream out = Files.newOutputStream(file.toPath())) {
                properties.store(out, "IPED analysis UI theme: system, light or dark");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not persist theme preference to {}", file, e);
        }
    }
}
