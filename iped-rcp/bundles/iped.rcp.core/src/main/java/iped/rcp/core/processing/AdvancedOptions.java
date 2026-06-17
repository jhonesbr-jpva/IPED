package iped.rcp.core.processing;

import java.nio.file.Path;
import java.util.List;

/**
 * Tier-C (Advanced) typed creation fields exposed on the wizard's
 * <em>Advanced</em> page (feature 005, FR-007 /
 * contracts/new-case-wizard.contract.md). Rare flags not modelled here go
 * through {@link NewCaseRequest#advancedParams()} ({@code -X k=v} / literal).
 *
 * @param blocksize     sector block size in bytes ({@code -b}); {@code null}/0 = unset
 * @param ocrSubset     categories/bookmarks to OCR ({@code -ocr}, repeatable)
 * @param noContent     categories/bookmarks excluded from report content ({@code -nocontent})
 * @param logFile       redirect the processing log ({@code -log}); {@code null} = default
 * @param noLogFile     {@code --nologfile} — log to standard output
 * @param splashMessage custom splash message ({@code -splash}); {@code null} = none
 */
public record AdvancedOptions(Integer blocksize, List<String> ocrSubset, List<String> noContent,
        Path logFile, boolean noLogFile, String splashMessage) {

    public AdvancedOptions {
        ocrSubset = ocrSubset == null ? List.of() : List.copyOf(ocrSubset);
        noContent = noContent == null ? List.of() : List.copyOf(noContent);
    }

    /** Empty advanced options (nothing emitted). */
    public static AdvancedOptions none() {
        return new AdvancedOptions(null, List.of(), List.of(), null, false, null);
    }
}
