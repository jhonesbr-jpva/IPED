package iped.rcp.core.profiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers and edits processing profiles under a release {@code profiles/}
 * directory (feature 005, contracts/profile-editor.contract.md).
 *
 * <p>
 * Discovery ({@link #listProfiles(Path)}) feeds the New Case wizard (US1, FR-006).
 * The file-driven config model ({@link #loadModel}, {@link #createProfile},
 * {@link #saveModel}, {@link #deleteProfile}) powers the full profile editor
 * (US3): it merges the release's canonical config (base) with a profile's
 * overrides, writes back <strong>only</strong> the overridden keys to keep
 * profiles lean, and treats the five built-in profiles as read-only templates
 * (FR-018).
 *
 * <p>
 * Toolkit-free and engine-free: every operation takes the {@code profiles/}
 * directory as an argument (the canonical config root is its parent — the layout
 * is universal across the repo and the release), so the service is fully
 * unit-testable and independent of how the install root is resolved.
 */
public class ProfileService {

    /** Valid profile name: letters, digits, dash, underscore, dot (safe folder name). */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /** Top-level canonical key=value config files (besides {@code conf/*}). */
    private static final List<String> ROOT_KV_FILES = List.of("IPEDConfig.txt", "LocalConfig.txt");

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

    /**
     * Loads the full, file-driven config model of {@code name}: the canonical
     * config (base) merged with the profile's overrides. The profile folder need
     * not exist (a brand-new or default profile simply has no overrides).
     *
     * @param profilesDir the release {@code profiles/} folder
     * @param name        the profile name
     * @return the merged model (base values, overridden where the profile redefines them)
     */
    public ProfileConfigModel loadModel(Path profilesDir, String name) {
        if (profilesDir == null || name == null) {
            throw new IllegalArgumentException("profilesDir and name are required");
        }
        Path baseRoot = profilesDir.getParent();
        if (baseRoot == null || !Files.isDirectory(baseRoot)) {
            return new ProfileConfigModel(name, List.of(), List.of());
        }
        Path profileDir = profilesDir.resolve(name);

        List<ConfigFileGroup> groups = new ArrayList<>();
        List<AdvancedFile> advancedFiles = new ArrayList<>();

        for (Path baseFile : collectBaseFiles(baseRoot)) {
            String relPath = relativize(baseRoot, baseFile);
            Path overrideFile = profileDir.resolve(relPath);
            if (isAdvanced(relPath)) {
                advancedFiles.add(loadAdvancedFile(relPath, baseFile, overrideFile));
            } else {
                ConfigFileGroup group = loadKeyValueGroup(relPath, baseFile, overrideFile);
                if (!group.options().isEmpty()) {
                    groups.add(group);
                }
            }
        }
        return new ProfileConfigModel(name, groups, advancedFiles);
    }

    /**
     * Creates a new {@link ProfileDescriptor.Kind#USER} profile folder. When
     * {@code basedOn} names an existing profile, its override files are cloned
     * into the new profile (FR-014/FR-015); otherwise the profile starts empty
     * (inherits all defaults).
     *
     * @throws IllegalArgumentException invalid name or name collision (FR-019)
     */
    public ProfileDescriptor createProfile(Path profilesDir, String name, String basedOn) {
        if (profilesDir == null) {
            throw new IllegalArgumentException("profilesDir is required");
        }
        if (!isValidProfileName(name)) {
            throw new IllegalArgumentException("Invalid profile name: " + name);
        }
        if (profileExists(profilesDir, name)) {
            throw new IllegalArgumentException("Profile already exists: " + name);
        }
        Path profileDir = profilesDir.resolve(name);
        try {
            Files.createDirectories(profileDir);
            if (basedOn != null && !basedOn.isBlank()) {
                Path sourceDir = profilesDir.resolve(basedOn);
                if (Files.isDirectory(sourceDir)) {
                    copyTree(sourceDir, profileDir);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create profile " + name, e);
        }
        return new ProfileDescriptor(name, ProfileDescriptor.Kind.USER, profileDir,
                basedOn != null && !basedOn.isBlank() ? basedOn : null);
    }

    /**
     * Writes back the profile's overrides: for each config file, only the keys /
     * advanced files marked {@code overridden} are written to
     * {@code profiles/<name>/}; files that no longer carry any override are
     * removed (lean profiles). Built-in profiles are read-only and rejected
     * (FR-018). UTF-8.
     *
     * @throws IllegalStateException when {@code name} is a built-in profile
     */
    public void saveModel(Path profilesDir, String name, ProfileConfigModel model) {
        if (profilesDir == null || name == null || model == null) {
            throw new IllegalArgumentException("profilesDir, name and model are required");
        }
        if (ProfileDescriptor.kindOf(name) == ProfileDescriptor.Kind.BUILT_IN) {
            throw new IllegalStateException("Built-in profiles are read-only: " + name);
        }
        Path profileDir = profilesDir.resolve(name);
        try {
            Files.createDirectories(profileDir);
            for (ConfigFileGroup group : model.groups()) {
                Path target = profileDir.resolve(group.fileName());
                List<ConfigOption> overrides = group.options().stream().filter(ConfigOption::overridden).toList();
                if (overrides.isEmpty()) {
                    Files.deleteIfExists(target);
                } else {
                    writeKeyValueOverride(target, overrides);
                }
            }
            for (AdvancedFile file : model.advancedFiles()) {
                Path target = profileDir.resolve(file.fileName());
                if (file.overridden()) {
                    ensureParent(target);
                    Files.writeString(target, file.content(), StandardCharsets.UTF_8);
                } else {
                    Files.deleteIfExists(target);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save profile " + name, e);
        }
    }

    /**
     * Deletes a user profile folder and its contents. Built-in profiles are
     * read-only and rejected (FR-018).
     *
     * @throws IllegalStateException when {@code name} is a built-in profile
     */
    public void deleteProfile(Path profilesDir, String name) {
        if (profilesDir == null || name == null) {
            throw new IllegalArgumentException("profilesDir and name are required");
        }
        if (ProfileDescriptor.kindOf(name) == ProfileDescriptor.Kind.BUILT_IN) {
            throw new IllegalStateException("Built-in profiles are read-only: " + name);
        }
        Path profileDir = profilesDir.resolve(name);
        if (!Files.exists(profileDir)) {
            return;
        }
        try {
            deleteTree(profileDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete profile " + name, e);
        }
    }

    // --- internals -------------------------------------------------------

    /** Canonical config files: the two root files plus everything under {@code conf/}. */
    private static List<Path> collectBaseFiles(Path baseRoot) {
        List<Path> files = new ArrayList<>();
        for (String rootFile : ROOT_KV_FILES) {
            Path f = baseRoot.resolve(rootFile);
            if (Files.isRegularFile(f)) {
                files.add(f);
            }
        }
        Path confDir = baseRoot.resolve("conf");
        if (Files.isDirectory(confDir)) {
            try (Stream<Path> entries = Files.list(confDir)) {
                entries.filter(Files::isRegularFile).filter(p -> isSupportedConfigFile(fileName(p)))
                        .sorted(Comparator.comparing(ProfileService::fileName)).forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to list " + confDir, e);
            }
        }
        return files;
    }

    private static boolean isSupportedConfigFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".txt") || n.endsWith(".properties") || n.endsWith(".xml") || n.endsWith(".json");
    }

    private static boolean isAdvanced(String relPath) {
        String n = relPath.toLowerCase();
        return n.endsWith(".xml") || n.endsWith(".json");
    }

    private static String fileName(Path p) {
        Path n = p.getFileName();
        return n == null ? "" : n.toString();
    }

    /** Path of {@code file} relative to {@code baseRoot}, normalized with '/'. */
    private static String relativize(Path baseRoot, Path file) {
        return baseRoot.relativize(file).toString().replace('\\', '/');
    }

    private ConfigFileGroup loadKeyValueGroup(String relPath, Path baseFile, Path overrideFile) {
        Map<String, ParsedEntry> base = parseKeyValueFile(baseFile);
        Map<String, ParsedEntry> overrides = Files.isRegularFile(overrideFile) ? parseKeyValueFile(overrideFile)
                : Map.of();

        List<ConfigOption> options = new ArrayList<>();
        for (Map.Entry<String, ParsedEntry> e : base.entrySet()) {
            String key = e.getKey();
            String baseValue = e.getValue().value();
            boolean overridden = overrides.containsKey(key);
            String effective = overridden ? overrides.get(key).value() : baseValue;
            options.add(new ConfigOption(key, baseValue, effective, overridden, e.getValue().description(),
                    ConfigOption.inferType(baseValue)));
        }
        // profile-only keys (present in the override but not in the base)
        for (Map.Entry<String, ParsedEntry> e : overrides.entrySet()) {
            if (!base.containsKey(e.getKey())) {
                String v = e.getValue().value();
                options.add(new ConfigOption(e.getKey(), null, v, true, e.getValue().description(),
                        ConfigOption.inferType(v)));
            }
        }
        return new ConfigFileGroup(relPath, options);
    }

    private AdvancedFile loadAdvancedFile(String relPath, Path baseFile, Path overrideFile) {
        String baseContent = readString(baseFile);
        if (Files.isRegularFile(overrideFile)) {
            return new AdvancedFile(relPath, baseContent, readString(overrideFile), true);
        }
        return new AdvancedFile(relPath, baseContent, baseContent, false);
    }

    /**
     * Parses a {@code key = value} config file preserving key order and capturing
     * the {@code #} comment block immediately preceding each key as its
     * description (decorative all-{@code #} lines and blank-line gaps are ignored).
     */
    static Map<String, ParsedEntry> parseKeyValueFile(Path file) {
        LinkedHashMap<String, ParsedEntry> result = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
        StringBuilder comment = new StringBuilder();
        boolean first = true;
        for (String raw : lines) {
            String line = raw;
            if (first) {
                line = stripBom(line);
                first = false;
            }
            String t = line.strip();
            if (t.isEmpty()) {
                comment.setLength(0);
                continue;
            }
            if (t.charAt(0) == '#') {
                String c = t.replaceFirst("^#+\\s?", "");
                if (!c.isBlank()) {
                    if (comment.length() > 0) {
                        comment.append('\n');
                    }
                    comment.append(c.stripTrailing());
                }
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0) {
                comment.setLength(0);
                continue;
            }
            String key = t.substring(0, eq).strip();
            String value = t.substring(eq + 1).strip();
            if (!key.isEmpty()) {
                result.put(key, new ParsedEntry(value, comment.toString()));
            }
            comment.setLength(0);
        }
        return result;
    }

    private static void writeKeyValueOverride(Path target, List<ConfigOption> overrides) throws IOException {
        ensureParent(target);
        StringBuilder sb = new StringBuilder();
        sb.append("# IPED profile override - generated by the profile editor.\n");
        sb.append("# Only keys redefined by this profile are listed; the rest inherit the defaults.\n\n");
        for (ConfigOption option : overrides) {
            sb.append(option.key()).append(" = ").append(option.effectiveValue() == null ? "" : option.effectiveValue())
                    .append('\n');
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    private static String stripBom(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '﻿') {
            i++;
        }
        return i == 0 ? line : line.substring(i);
    }

    private static void ensureParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static void copyTree(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dest.resolve(source.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Parsed {@code key = value} entry: the value plus the preceding {@code #} comment block. */
    record ParsedEntry(String value, String description) {
    }
}
