package iped.semantic.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Assume;
import org.junit.Test;

/**
 * SC-017, FR-042: with a semantic key on and its model source unusable, processing refuses to
 * start, naming what is missing, and the evidence is never opened. The engine initializes the tasks
 * before its item producers start, and a producer announces each source it opens with
 * {@code Adding '<source>'}: that line must not appear.
 *
 * <p>
 * Runs the release ({@code -Diped.semantic.test.ipedRoot}) on the reference evidence
 * ({@code -Diped.semantic.test.referenceEvidence}) with a processing profile made for the run and
 * removed afterwards.
 * </p>
 */
public class FailFastIT {

    private static final String PROFILE = "semantic-failfast-it";

    @Test
    public void aMissingLocalVisualModelRefusesToStart() throws Exception {
        refuses("visualLocalModel = models/semantic/visual/does-not-exist\r\n",
                "local model missing", "does-not-exist");
    }

    @Test
    public void anUnreachableRemoteSourceRefusesToStart() throws Exception {
        refuses("visualModelSource = remote\r\nvisualRemoteProtocol = iped-embed/1\r\n"
                + "visualRemoteEndpoint = http://127.0.0.1:1/iped\r\nremoteMaxRetries = 0\r\nremoteTimeoutSeconds = 2\r\n",
                "did not answer", "http://127.0.0.1:1/iped");
    }

    private void refuses(String semanticConf, String why, String what) throws Exception {
        String root = System.getProperty(ProcessedCases.IPED_ROOT_PROPERTY);
        String ev = System.getProperty(ProcessedCases.EVIDENCE_PROPERTY);
        Assume.assumeTrue("needs -D" + ProcessedCases.IPED_ROOT_PROPERTY + " and -D" + ProcessedCases.EVIDENCE_PROPERTY,
                root != null && ev != null);
        File ipedRoot = new File(root);
        Path profile = ipedRoot.toPath().resolve("profiles").resolve(PROFILE);
        Path base = Paths.get(System.getProperty("user.dir"), "target", "it-failfast").toAbsolutePath();
        ProcessedCases.deleteTree(base);
        ProcessedCases.deleteTree(profile);
        Files.createDirectories(profile.resolve("conf"));
        Files.createDirectories(base);
        try {
            Files.write(profile.resolve("IPEDConfig.txt"),
                    "enableSemanticText = false\r\nenableSemanticImages = true\r\nenableSemanticVideos = false\r\n"
                            .getBytes(StandardCharsets.UTF_8));
            Files.write(profile.resolve("conf").resolve("SemanticSearchConfig.txt"),
                    semanticConf.getBytes(StandardCharsets.UTF_8));
            Path log = base.resolve("failfast.log");
            int exit = ProcessedCases.run(ipedRoot, new File(ev), base.resolve("case").toFile(), PROFILE, null, log);
            String text = new String(Files.readAllBytes(log), StandardCharsets.ISO_8859_1);
            int refusal = text.indexOf("so processing does not start");
            assertTrue("the refusal is in the log:\n" + ProcessedCases.tail(log, 40), refusal >= 0);
            String line = text.substring(refusal - Math.min(refusal, 300), Math.min(text.length(), refusal + 400));
            assertTrue("it names the key: " + line, line.contains("enableSemanticImages"));
            assertTrue("and why: " + line, line.contains(why));
            assertTrue("and what: " + line, line.contains(what));
            assertFalse("the evidence was never opened", text.contains("Adding '"));
            assertFalse("no item was found", text.contains("Total items found"));
            assertTrue("the processing did not report success (exit " + exit + ")",
                    exit != 0 || !text.contains("IPED finished"));
        } finally {
            ProcessedCases.deleteTree(profile);
        }
    }
}
