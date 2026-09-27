package iped.semantic.task;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.configuration.IConfigurationDirectory;
import iped.semantic.TestSupport;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.utils.UTF8Properties;

/** The local model goes into the case, so the case's own analysis app can search (research D17). */
public class CaseModelsTest {

    private static final String MODEL = "models/semantic/text/tiny";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private String previousRoot;
    private File install;
    private File caseIped;

    @Before
    public void installation() throws Exception {
        TestSupport.ensureConfiguration();
        install = temp.newFolder("install");
        caseIped = temp.newFolder("case", "iped");
        Path model = install.toPath().resolve(MODEL);
        Files.createDirectories(model.resolve("tokenizer"));
        Files.write(model.resolve("model.json"), "{\"id\":\"tiny@1\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(model.resolve("model.onnx"), new byte[] { 1, 2, 3, 4 });
        Files.write(model.resolve("tokenizer/vocab.txt"), "a\nb\n".getBytes(StandardCharsets.UTF_8));
        previousRoot = System.getProperty(IConfigurationDirectory.IPED_APP_ROOT);
        System.setProperty(IConfigurationDirectory.IPED_APP_ROOT, install.getAbsolutePath());
    }

    @After
    public void restore() {
        if (previousRoot == null) {
            System.clearProperty(IConfigurationDirectory.IPED_APP_ROOT);
        } else {
            System.setProperty(IConfigurationDirectory.IPED_APP_ROOT, previousRoot);
        }
    }

    private static SemanticSearchConfig config(String key, String value) {
        UTF8Properties p = new UTF8Properties();
        p.setProperty("textLocalModel", MODEL);
        if (key != null) {
            p.setProperty(key, value);
        }
        SemanticSearchConfig c = new SemanticSearchConfig();
        c.processProperties(p);
        return c;
    }

    @Test
    public void theModelIsCopiedToTheSameRelativePathInsideTheCase() throws Exception {
        Path copied = CaseModels.copyIntoCase(config(null, null), Modality.TEXT, caseIped);
        assertEquals(caseIped.toPath().resolve(MODEL).toAbsolutePath().normalize(), copied);
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, Files.readAllBytes(copied.resolve("model.onnx")));
        assertTrue(Files.isRegularFile(copied.resolve("tokenizer/vocab.txt")));
        assertFalse("no partial copy left behind", Files.exists(copied.resolveSibling("tiny.copying")));
    }

    @Test
    public void theSameModelAlreadyInTheCaseIsNotCopiedAgain() throws Exception {
        Path copied = CaseModels.copyIntoCase(config(null, null), Modality.TEXT, caseIped);
        FileTime mark = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(copied.resolve("model.onnx"), mark);
        CaseModels.copyIntoCase(config(null, null), Modality.TEXT, caseIped);
        assertEquals(mark, Files.getLastModifiedTime(copied.resolve("model.onnx")));
    }

    @Test
    public void anotherModelInTheCaseIsReplaced() throws Exception {
        Path copied = CaseModels.copyIntoCase(config(null, null), Modality.TEXT, caseIped);
        Files.write(copied.resolve("model.json"), "{\"id\":\"old@0\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(copied.resolve("stale.bin"), new byte[] { 9 });
        CaseModels.copyIntoCase(config(null, null), Modality.TEXT, caseIped);
        assertEquals("{\"id\":\"tiny@1\"}", new String(Files.readAllBytes(copied.resolve("model.json")),
                StandardCharsets.UTF_8));
        assertFalse(Files.exists(copied.resolve("stale.bin")));
    }

    @Test
    public void aRemoteSourceOrAnAbsolutePathPutsNothingInTheCase() throws Exception {
        SemanticSearchConfig remote = config("textModelSource", "remote");
        assertNull(CaseModels.copyIntoCase(remote, Modality.TEXT, caseIped));
        SemanticSearchConfig absolute = config("textLocalModel", install.toPath().resolve(MODEL).toString());
        assertNull(CaseModels.copyIntoCase(absolute, Modality.TEXT, caseIped));
        assertFalse(Files.exists(caseIped.toPath().resolve("models")));
    }
}
