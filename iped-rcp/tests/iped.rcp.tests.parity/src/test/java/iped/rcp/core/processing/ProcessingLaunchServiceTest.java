package iped.rcp.core.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T010 (US1) — unit coverage of {@link ProcessingLaunchService}'s pure parts:
 * full command assembly ({@code <java> -jar iped.jar <args>}) and install-root
 * resolution via the {@code iped.install.dir} override
 * (contracts/processing-launch.contract.md). The actual subprocess spawn and
 * the FR-024 conflict guard are exercised by manual/integration runs (a real
 * {@code iped.jar} install); these tests never spawn a process.
 *
 * <p>
 * Same package as the class under test so the package-visible helpers are
 * reachable without widening their visibility.
 */
class ProcessingLaunchServiceTest {

    private static NewCaseRequest minimal(Path source, Path output) {
        return new NewCaseRequest(List.of(DataSourceEntry.of(source)), output, "triage", ProcessingMode.NEW, null,
                null, CommonOptions.defaults(), AdvancedOptions.none(), Map.of());
    }

    @Test
    void buildFullCommandPrependsJavaJarIpedJar(@TempDir Path tmp) {
        Path java = tmp.resolve("jre/bin/java");
        Path ipedJar = tmp.resolve("iped.jar");
        NewCaseRequest req = minimal(tmp.resolve("ev"), tmp.resolve("case"));

        List<String> cmd = ProcessingLaunchService.buildFullCommand(java, ipedJar, req);

        assertEquals(java.toString(), cmd.get(0));
        assertEquals("-jar", cmd.get(1));
        assertEquals(ipedJar.toString(), cmd.get(2));
        assertEquals("-d", cmd.get(3), "Bootstrap args must follow the jar");
        assertTrue(cmd.contains("-profile"));
        assertTrue(cmd.contains("triage"));
    }

    @Test
    void resolveInstallRootHonoursOverride(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("iped-root"));
        String previous = System.getProperty(ProcessingLaunchService.INSTALL_DIR_PROP);
        try {
            System.setProperty(ProcessingLaunchService.INSTALL_DIR_PROP, root.toString());
            assertEquals(root, ProcessingLaunchService.resolveInstallRoot());

            System.setProperty(ProcessingLaunchService.INSTALL_DIR_PROP, tmp.resolve("does-not-exist").toString());
            assertNull(ProcessingLaunchService.resolveInstallRoot(), "non-existent override resolves to null");
        } finally {
            restore(ProcessingLaunchService.INSTALL_DIR_PROP, previous);
        }
    }

    @Test
    void launchRejectsInvalidRequest() {
        // Empty request (no sources/output) must fail fast before any process.
        NewCaseRequest invalid = new NewCaseRequest(List.of(), null, "triage", ProcessingMode.NEW, null, null,
                CommonOptions.defaults(), AdvancedOptions.none(), Map.of());
        assertThrows(LaunchException.class, () -> new ProcessingLaunchService().launch(invalid));
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
