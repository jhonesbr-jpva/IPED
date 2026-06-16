package iped.rcp.tests.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import iped.rcp.core.processing.AdvancedOptions;
import iped.rcp.core.processing.BootstrapCommandBuilder;
import iped.rcp.core.processing.CommonOptions;
import iped.rcp.core.processing.DataSourceEntry;
import iped.rcp.core.processing.NewCaseRequest;
import iped.rcp.core.processing.ProcessingMode;

/**
 * T011 (US1) — unit coverage of {@link BootstrapCommandBuilder}: the
 * NewCaseRequest → Bootstrap argument mapping
 * (contracts/new-case-wizard.contract.md) and the FR-008 validations that gate
 * a launch (SC-003). Pure logic — no case data required, always runs.
 */
class BootstrapCommandBuilderTest {

    private static NewCaseRequest minimal(Path source, Path output, ProcessingMode mode) {
        return new NewCaseRequest(List.of(DataSourceEntry.of(source)), output, "triage", mode, null, null,
                CommonOptions.defaults(), AdvancedOptions.none(), Map.of());
    }

    @Test
    void mapsCoreArgsInOrderAndNeverNogui(@TempDir Path tmp) throws IOException {
        Path source = Files.createDirectories(tmp.resolve("evidence"));
        Path output = tmp.resolve("case");
        NewCaseRequest req = minimal(source, output, ProcessingMode.NEW);

        List<String> args = BootstrapCommandBuilder.buildArgs(req);

        assertEquals("-d", args.get(0));
        assertEquals(source.toString(), args.get(1));
        assertTrue(indexOfPair(args, "-o", output.toString()) >= 0, "missing -o <output>");
        assertTrue(indexOfPair(args, "-profile", "triage") >= 0, "missing -profile triage");
        assertFalse(args.contains("--nogui"), "interactive flow must never pass --nogui");
        assertFalse(args.contains("--append"), "NEW mode must not add a mode flag");
    }

    @Test
    void mapsDnamePasswordModeAndOptions(@TempDir Path tmp) throws IOException {
        Path source = Files.createDirectories(tmp.resolve("img.E01"));
        Path output = Files.createDirectories(tmp.resolve("case"));
        Files.createDirectories(output.resolve("iped")); // existing case for APPEND

        NewCaseRequest req = new NewCaseRequest(
                List.of(new DataSourceEntry(source, "Phone", "s3cr3t")), output, "forensic",
                ProcessingMode.APPEND, "GMT-3", null,
                new CommonOptions(true, true, false, false, false),
                new AdvancedOptions(4096, List.of("Images"), List.of(), null, false, null),
                Map.of("key", "val"));

        List<String> args = BootstrapCommandBuilder.buildArgs(req);

        // -dname / -p stay adjacent to their -d
        int d = args.indexOf("-d");
        assertEquals("-dname", args.get(d + 2));
        assertEquals("Phone", args.get(d + 3));
        assertEquals("-p", args.get(d + 4));
        assertTrue(args.contains("--append"));
        assertTrue(indexOfPair(args, "-tz", "GMT-3") >= 0);
        assertTrue(args.contains("--addowner"));
        assertTrue(args.contains("--portable"));
        assertTrue(indexOfPair(args, "-b", "4096") >= 0);
        assertTrue(indexOfPair(args, "-ocr", "Images") >= 0);
        assertTrue(args.contains("-Xkey=val"), "dynamic param must be a single -Xk=v token");
    }

    @Test
    void validateFlagsMissingSourcesAndOutput() {
        NewCaseRequest empty = new NewCaseRequest(List.of(), null, "triage", ProcessingMode.NEW, null, null,
                CommonOptions.defaults(), AdvancedOptions.none(), Map.of());
        List<String> errors = BootstrapCommandBuilder.validate(empty);
        assertFalse(errors.isEmpty());
        assertFalse(BootstrapCommandBuilder.isValid(empty));
    }

    @Test
    void validateRejectsNewOverExistingCaseAndAppendWithout(@TempDir Path tmp) throws IOException {
        Path source = Files.createDirectories(tmp.resolve("evidence"));
        Path output = Files.createDirectories(tmp.resolve("case"));

        // APPEND without an existing case → invalid
        assertFalse(BootstrapCommandBuilder.isValid(minimal(source, output, ProcessingMode.APPEND)));

        // NEW over an existing case → invalid
        Files.createDirectories(output.resolve("iped"));
        assertFalse(BootstrapCommandBuilder.isValid(minimal(source, output, ProcessingMode.NEW)));

        // APPEND now valid (case exists)
        assertTrue(BootstrapCommandBuilder.isValid(minimal(source, output, ProcessingMode.APPEND)));
    }

    @Test
    void validateRejectsOutputInsideSource(@TempDir Path tmp) throws IOException {
        Path source = Files.createDirectories(tmp.resolve("evidence"));
        Path output = source.resolve("nested-case"); // inside the source
        assertFalse(BootstrapCommandBuilder.isValid(minimal(source, output, ProcessingMode.NEW)));
    }

    private static int indexOfPair(List<String> args, String flag, String value) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if (args.get(i).equals(flag) && args.get(i + 1).equals(value)) {
                return i;
            }
        }
        return -1;
    }
}
