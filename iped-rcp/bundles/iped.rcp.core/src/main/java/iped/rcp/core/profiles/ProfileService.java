package iped.rcp.core.profiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers and (in feature 005 US3) edits processing profiles under a release
 * {@code profiles/} directory (contracts/profile-editor.contract.md).
 *
 * <p>
 * This first increment implements <strong>discovery only</strong>
 * ({@link #listProfiles(Path)}), consumed by the New Case wizard's profile
 * page (FR-006) — the config-model read/write (load/create/save/delete) is
 * added by US3 (tasks T024-T027).
 *
 * <p>
 * Toolkit-free; takes the {@code profiles} directory as an argument so it is
 * unit-testable and independent of how the install root is resolved.
 */
public class ProfileService {

    /** Valid profile name: letters, digits, dash, underscore, dot (safe folder name). */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * Lists the profiles found directly under {@code profilesDir} (each
     * sub-folder is a profile), built-in ones flagged by name. Returns an empty
     * list when the directory does not exist.
     *
     * @param profilesDir the release {@code profiles/} folder
     * @return profiles sorted built-in first, then by name (case-insensitive)
     */
    public List<ProfileDescriptor> listProfiles(Path profilesDir) {
        if (profilesDir == null || !Files.isDirectory(profilesDir)) {
            return List.of();
        }
        List<ProfileDescriptor> profiles = new ArrayList<>();
        try (Stream<Path> entries = Files.list(profilesDir)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                Path namePath = dir.getFileName();
                if (namePath == null) {
                    return;
                }
                String name = namePath.toString();
                profiles.add(new ProfileDescriptor(name, ProfileDescriptor.kindOf(name), dir, null));
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list profiles in " + profilesDir, e);
        }
        profiles.sort(Comparator
                .comparing((ProfileDescriptor p) -> p.isBuiltIn() ? 0 : 1)
                .thenComparing(p -> p.name().toLowerCase()));
        return profiles;
    }

    /** @return true when {@code name} is a syntactically valid, non-blank profile name (FR-019). */
    public boolean isValidProfileName(String name) {
        return name != null && !name.isBlank() && VALID_NAME.matcher(name).matches();
    }

    /**
     * @return true when a profile folder named {@code name} already exists under
     *         {@code profilesDir} (name-collision check, FR-019)
     */
    public boolean profileExists(Path profilesDir, String name) {
        return name != null && profilesDir != null && Files.isDirectory(profilesDir.resolve(name));
    }
}
