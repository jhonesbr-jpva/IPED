package iped.rcp.core.profiles;

import java.util.List;
import java.util.Optional;

/**
 * The full, file-driven configuration model of a profile that the editor renders
 * (feature 005, US3, data-model §4 / contracts/profile-editor.contract.md).
 *
 * <p>
 * It is the merge of the release's canonical config (base) with the edited
 * profile's overrides: each {@link ConfigOption#effectiveValue()} is the base
 * value overridden by the profile, and {@link ConfigOption#overridden()} marks
 * the keys the profile redefines. {@code key=value} files become
 * {@link #groups()}; {@code .xml}/{@code .json} files become
 * {@link #advancedFiles()}.
 *
 * @param profileName    the profile this model belongs to
 * @param groups         one {@link ConfigFileGroup} per canonical key=value file
 * @param advancedFiles  the raw-text {@code .xml}/{@code .json} files
 */
public record ProfileConfigModel(String profileName, List<ConfigFileGroup> groups, List<AdvancedFile> advancedFiles) {

    public ProfileConfigModel {
        groups = groups == null ? List.of() : groups;
        advancedFiles = advancedFiles == null ? List.of() : advancedFiles;
    }

    /** @return total number of options the profile overrides across all files. */
    public long overriddenOptionCount() {
        return groups.stream().flatMap(g -> g.options().stream()).filter(ConfigOption::overridden).count();
    }

    /** @return the option for {@code key} in {@code fileName}, if present. */
    public Optional<ConfigOption> findOption(String fileName, String key) {
        return groups.stream().filter(g -> g.fileName().equals(fileName)).flatMap(g -> g.options().stream())
                .filter(o -> o.key().equals(key)).findFirst();
    }
}
