package iped.rcp.core.profiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T030 (US3) — headless coverage of the file-driven {@link ProfileService}
 * editor operations: base+override merge, save round-trip (overrides only,
 * stale removal), built-in isolation, and name collision/validation
 * (FR-018/FR-019, contracts/profile-editor.contract.md). Pure logic over a
 * synthetic config root — no release or case data required.
 */
class ProfileServiceTest {

    private final ProfileService service = new ProfileService();

    /** Builds a synthetic {@code config/} root and returns its {@code profiles/} dir. */
    private Path setUpConfigRoot(Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Files.writeString(root.resolve("IPEDConfig.txt"), """
                ########################################################################
                # Processing Settings
                ########################################################################

                # Enables file hashes calculation.
                enableHash = true

                # Enables carving.
                enableCarving = false

                # Number of carving threads.
                carvingThreads = 4
                """, StandardCharsets.UTF_8);
        Path conf = Files.createDirectories(root.resolve("conf"));
        Files.writeString(conf.resolve("CarverConfig.xml"), "<carving>\n  <base/>\n</carving>\n",
                StandardCharsets.UTF_8);
        return Files.createDirectories(root.resolve("profiles"));
    }

    @Test
    void loadModelMergesBaseWithProfileOverrides(@TempDir Path tmp) throws IOException {
        Path profilesDir = setUpConfigRoot(tmp);
        Path forensic = Files.createDirectories(profilesDir.resolve("forensic"));
        Files.writeString(forensic.resolve("IPEDConfig.txt"), "enableCarving = true\n", StandardCharsets.UTF_8);

        ProfileConfigModel model = service.loadModel(profilesDir, "forensic");

        ConfigOption hash = model.findOption("IPEDConfig.txt", "enableHash").orElseThrow();
        assertEquals("true", hash.effectiveValue());
        assertFalse(hash.overridden(), "enableHash is not redefined by the profile");
        assertEquals("Enables file hashes calculation.", hash.description());
        assertEquals(ConfigOption.Type.BOOLEAN, hash.type());

        ConfigOption carving = model.findOption("IPEDConfig.txt", "enableCarving").orElseThrow();
        assertEquals("true", carving.effectiveValue(), "overridden by the profile");
        assertEquals("false", carving.baseValue());
        assertTrue(carving.overridden());

        ConfigOption threads = model.findOption("IPEDConfig.txt", "carvingThreads").orElseThrow();
        assertEquals(ConfigOption.Type.INT, threads.type());

        AdvancedFile xml = model.advancedFiles().stream().filter(f -> f.fileName().equals("conf/CarverConfig.xml"))
                .findFirst().orElseThrow();
        assertFalse(xml.overridden());
        assertTrue(xml.content().contains("<base/>"));
    }

    @Test
    void saveModelWritesOnlyOverridesAndPrunesStaleKeys(@TempDir Path tmp) throws IOException {
        Path profilesDir = setUpConfigRoot(tmp);
        // clone the (synthetic) base into a user profile, pre-seeded with one override
        Path forensic = Files.createDirectories(profilesDir.resolve("forensic"));
        Files.writeString(forensic.resolve("IPEDConfig.txt"), "enableCarving = true\n", StandardCharsets.UTF_8);

        ProfileDescriptor created = service.createProfile(profilesDir, "my-case", "forensic");
        assertEquals(ProfileDescriptor.Kind.USER, created.kind());
        assertEquals("forensic", created.basedOn());
        assertTrue(service.profileExists(profilesDir, "my-case"));

        ProfileConfigModel model = service.loadModel(profilesDir, "my-case");
        // redefine enableHash, drop the inherited enableCarving override
        model.findOption("IPEDConfig.txt", "enableHash").orElseThrow().setValue("false");
        model.findOption("IPEDConfig.txt", "enableCarving").orElseThrow().resetToBase();

        service.saveModel(profilesDir, "my-case", model);

        Path written = profilesDir.resolve("my-case").resolve("IPEDConfig.txt");
        String content = Files.readString(written, StandardCharsets.UTF_8);
        assertTrue(content.contains("enableHash = false"), "override must be written");
        assertFalse(content.contains("enableCarving"), "reset key must not be written (lean profile)");

        // round-trip: reload reflects exactly the saved overrides
        ProfileConfigModel reloaded = service.loadModel(profilesDir, "my-case");
        assertTrue(reloaded.findOption("IPEDConfig.txt", "enableHash").orElseThrow().overridden());
        assertFalse(reloaded.findOption("IPEDConfig.txt", "enableCarving").orElseThrow().overridden());
        assertEquals(1, reloaded.overriddenOptionCount());
    }

    @Test
    void advancedFileRoundTrip(@TempDir Path tmp) throws IOException {
        Path profilesDir = setUpConfigRoot(tmp);
        service.createProfile(profilesDir, "adv", null);

        ProfileConfigModel model = service.loadModel(profilesDir, "adv");
        AdvancedFile xml = model.advancedFiles().stream().filter(f -> f.fileName().equals("conf/CarverConfig.xml"))
                .findFirst().orElseThrow();
        xml.setContent("<carving>\n  <custom/>\n</carving>\n");
        assertTrue(xml.overridden());
        service.saveModel(profilesDir, "adv", model);

        Path written = profilesDir.resolve("adv").resolve("conf").resolve("CarverConfig.xml");
        assertTrue(Files.exists(written));
        assertTrue(Files.readString(written, StandardCharsets.UTF_8).contains("<custom/>"));

        // reset → override file pruned on save
        ProfileConfigModel reloaded = service.loadModel(profilesDir, "adv");
        reloaded.advancedFiles().stream().filter(f -> f.fileName().equals("conf/CarverConfig.xml")).findFirst()
                .orElseThrow().resetToBase();
        service.saveModel(profilesDir, "adv", reloaded);
        assertFalse(Files.exists(written), "non-overridden advanced file must be pruned");
    }

    @Test
    void builtInProfilesAreReadOnly(@TempDir Path tmp) throws IOException {
        Path profilesDir = setUpConfigRoot(tmp);
        Files.createDirectories(profilesDir.resolve("forensic"));
        ProfileConfigModel model = service.loadModel(profilesDir, "forensic");

        assertThrows(IllegalStateException.class, () -> service.saveModel(profilesDir, "forensic", model));
        assertThrows(IllegalStateException.class, () -> service.deleteProfile(profilesDir, "forensic"));
    }

    @Test
    void createRejectsCollisionAndInvalidNames(@TempDir Path tmp) throws IOException {
        Path profilesDir = setUpConfigRoot(tmp);
        service.createProfile(profilesDir, "dup", null);

        assertThrows(IllegalArgumentException.class, () -> service.createProfile(profilesDir, "dup", null));
        assertThrows(IllegalArgumentException.class, () -> service.createProfile(profilesDir, "bad name!", null));
        assertFalse(service.isValidProfileName("bad name!"));
        assertTrue(service.isValidProfileName("ok-name_1.2"));
    }

    @Test
    void deleteRemovesUserProfile(@TempDir Path tmp) throws IOException {
        Path profilesDir = setUpConfigRoot(tmp);
        service.createProfile(profilesDir, "temp", null);
        assertTrue(service.profileExists(profilesDir, "temp"));

        service.deleteProfile(profilesDir, "temp");
        assertFalse(service.profileExists(profilesDir, "temp"));
    }
}
