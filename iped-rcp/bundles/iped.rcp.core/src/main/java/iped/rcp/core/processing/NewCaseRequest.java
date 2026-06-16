package iped.rcp.core.processing;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Immutable capture of the New Case wizard choices (feature 005, data-model
 * §1). Built incrementally by the wizard pages and <em>frozen</em> on Finish;
 * consumed by {@link BootstrapCommandBuilder} and the
 * {@code ProcessingLaunchService}.
 *
 * <p>
 * The toolkit-free model so the mapping/validation can be unit-tested without
 * the e4 UI (task T011).
 *
 * @param dataSources    1+ evidence sources ({@code -d}); never empty
 * @param outputDir      case output folder ({@code -o})
 * @param profileName    processing profile name ({@code -profile})
 * @param mode           {@link ProcessingMode}
 * @param timezone       optional FAT timezone ({@code -tz}); {@code null} = system
 * @param keywordsFile   optional initial keyword list ({@code -l}); {@code null} = none
 * @param commonOptions  tier-B toggles
 * @param advancedOptions tier-C typed fields
 * @param advancedParams literal/dynamic passthrough ({@code -X k=v} / flags)
 */
public record NewCaseRequest(List<DataSourceEntry> dataSources, Path outputDir, String profileName,
        ProcessingMode mode, String timezone, Path keywordsFile, CommonOptions commonOptions,
        AdvancedOptions advancedOptions, Map<String, String> advancedParams) {

    public NewCaseRequest {
        dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
        advancedParams = advancedParams == null ? Map.of() : Map.copyOf(advancedParams);
        if (mode == null) {
            mode = ProcessingMode.NEW;
        }
        if (commonOptions == null) {
            commonOptions = CommonOptions.defaults();
        }
        if (advancedOptions == null) {
            advancedOptions = AdvancedOptions.none();
        }
    }

    /** @return the case folder that processing will create/update ({@code outputDir/iped}). */
    public Path caseDir() {
        return outputDir == null ? null : outputDir.resolve("iped");
    }
}
