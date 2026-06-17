package iped.rcp.core.profiles;

import java.util.List;

/**
 * The options of one canonical {@code key = value} config file of a profile
 * (feature 005, US3, data-model §4). One group per relevant config file
 * ({@code IPEDConfig.txt}, {@code LocalConfig.txt}, {@code conf/*.txt|.properties}).
 *
 * @param fileName the config file path relative to the config root (e.g.
 *                 {@code IPEDConfig.txt} or {@code conf/IndexTaskConfig.txt})
 * @param options  the options found in the file, in file order (mutated in place
 *                 by the editor; the list itself is not resized)
 */
public record ConfigFileGroup(String fileName, List<ConfigOption> options) {

    public ConfigFileGroup {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("ConfigFileGroup.fileName must not be blank");
        }
        options = options == null ? List.of() : options;
    }

    /** @return true when at least one option is overridden by the profile being edited. */
    public boolean hasOverrides() {
        return options.stream().anyMatch(ConfigOption::overridden);
    }
}
