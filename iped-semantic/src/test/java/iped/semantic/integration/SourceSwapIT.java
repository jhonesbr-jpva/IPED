package iped.semantic.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import iped.semantic.FakeEmbedder;
import iped.semantic.SemanticPaths;
import iped.semantic.SyntheticCase;
import iped.semantic.TestSupport;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.Embedder;
import iped.semantic.model.Embedder.Role;
import iped.semantic.model.EmbedderFactory;
import iped.semantic.model.LocalOnnxEmbedder;
import iped.semantic.model.RemoteEmbedders;
import iped.semantic.search.Availability;
import iped.semantic.search.Ordering;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.store.BuildRecord;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;
import iped.utils.UTF8Properties;

/**
 * Amendment A4: what a case requires is the model, not the source. A case prepared through a remote
 * iped-embed/1 service is searchable with the identical local artifact, and the other way round; a
 * different artifact is refused as {@code MODEL_INCOMPATIBLE}. The fingerprint (research D10) is
 * what decides.
 *
 * <p>
 * The remote service is, in turn, an iped-embed/1 server inside this JVM serving the local model
 * ({@code -Diped.semantic.test.textModel}), and the Python reference server of
 * {@code tools/semantic-server} ({@code -Diped.semantic.test.referenceServerPython=<python with its
 * requirements>}). Each variant is skipped without its parameter.
 * </p>
 */
public class SourceSwapIT {

    public static final String PYTHON_PROPERTY = "iped.semantic.test.referenceServerPython";
    private static final String SECRET = "swap-it-credential";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void throughAServerOfTheSameArtifactInThisJvm() throws Exception {
        Path modelDir = TestSupport.requireTextModel();
        LocalOnnxEmbedder local = LocalOnnxEmbedder.open(modelDir, SemanticPaths.nativeDir());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            ObjectNode out = JSON.createObjectNode();
            int status = 200;
            if (!("Bearer " + SECRET).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
            } else if (ex.getRequestURI().getPath().endsWith("/model")) {
                out.put("id", local.identity().getId());
                out.put("sha256", local.identity().getSha256());
                out.put("dimensions", local.identity().getDimensions());
                out.putArray("modalities").add("text");
            } else {
                JsonNode request = JSON.readTree(in);
                ObjectNode model = out.putObject("model");
                model.put("id", local.identity().getId());
                model.put("sha256", local.identity().getSha256());
                ArrayNode embeddings = out.putArray("embeddings");
                try {
                    for (JsonNode input : request.path("inputs")) {
                        Role role = "query".equals(input.path("role").asText()) ? Role.QUERY : Role.PASSAGE;
                        ArrayNode v = embeddings.addArray();
                        for (float x : local.embedTexts(role, List.of(input.path("text").asText()))[0]) {
                            v.add(x);
                        }
                    }
                } catch (Exception e) {
                    status = 500;
                }
            }
            byte[] body = JSON.writeValueAsBytes(out);
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        try {
            swap(modelDir, "http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void throughThePythonReferenceServer() throws Exception {
        Path modelDir = TestSupport.requireTextModel();
        String python = System.getProperty(PYTHON_PROPERTY);
        Assume.assumeTrue("pass -D" + PYTHON_PROPERTY + "=<python with tools/semantic-server/requirements.txt>",
                python != null);
        Path script = TestSupport.repoRoot().resolve("iped-semantic/tools/semantic-server/semantic_server.py");
        ProcessBuilder pb = new ProcessBuilder(python, script.toString(), "--model-dir", modelDir.toString(), "--port",
                "0").redirectErrorStream(true);
        pb.environment().put("IPED_EMBED_CREDENTIAL", SECRET);
        Process p = pb.start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String endpoint = null;
            List<String> lines = new ArrayList<>();
            for (String line = out.readLine(); line != null; line = out.readLine()) {
                lines.add(line);
                int at = line.indexOf(" at http://");
                if (line.startsWith("serving ") && at > 0) {
                    endpoint = line.substring(at + 4, line.lastIndexOf("/iped-embed/1"));
                    break;
                }
            }
            assertTrue("the reference server did not start: " + lines, endpoint != null);
            Thread drain = new Thread(() -> {
                try {
                    while (out.readLine() != null) {
                        // the server logs method and path only
                    }
                } catch (Exception ignored) {
                    // process ended
                }
            });
            drain.setDaemon(true);
            drain.start();
            swap(modelDir, endpoint);
        } finally {
            p.destroy();
            p.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** Prepare remotely, search locally and remotely, and refuse another artifact. */
    private void swap(Path modelDir, String endpoint) throws Exception {
        Path credential = Files.createTempFile("swap", ".cred");
        Files.write(credential, SECRET.getBytes(StandardCharsets.UTF_8));
        try {
            SemanticSearchConfig remote = config(modelDir, "remote", endpoint, credential);
            SemanticSearchConfig local = config(modelDir, "local", endpoint, credential);

            SyntheticCase c = new SyntheticCase("swap", remote);
            Embedder viaRemote = RemoteEmbedders.open(remote, Modality.TEXT, 10);
            TextPreparation p = c.open(viaRemote, null);
            List<TextItem> items = new ArrayList<>();
            items.add(c.doc(1, "Deixa o pacote com o moleque da portaria, o acerto é amanhã."));
            items.add(c.doc(2, "Receita de bolo de cenoura com cobertura de chocolate."));
            items.add(c.doc(3, "Relatório trimestral de vendas da filial."));
            c.prepareAll(p, items);
            p.finish();

            BuildRecord.ModalityRecord mr = BuildRecord.read(c.semanticDir().resolve("build-record.json"))
                    .get(Modality.TEXT);
            assertEquals("remote", mr.source);
            assertEquals("the destination of the preparation is recorded", "preparation", mr.remote.phase);
            assertTrue(mr.remote.destination.startsWith("http://127.0.0.1:"));
            assertFalse("never the credential", new String(Files.readAllBytes(c.semanticDir().resolve(
                    "build-record.json")), StandardCharsets.UTF_8).contains(SECRET));

            // searched with the identical local artifact: available, and says where it was prepared
            try (SemanticSearcher s = new SemanticSearcher(c.moduleDir, c.items, local, new EmbedderFactory(local))) {
                Availability a = s.availability(Modality.TEXT);
                assertTrue("local search of a remotely prepared case: " + a.getDetail(), a.isAvailable());
                Ordering o = s.search(SemanticRequest.builder().query("entrega de mercadoria e pagamento combinado")
                        .build(local)).get(0);
                assertEquals("local", o.getModel().getSource());
                assertEquals("remote", o.getModel().getPreparedWith());
                assertNull("nothing was sent anywhere", o.getModel().getSentTo());
                assertEquals(1, o.getAll().get(0).getItemId());
            }
            // and remotely: the question's destination is declared
            try (SemanticSearcher s = new SemanticSearcher(c.moduleDir, c.items, remote,
                    new EmbedderFactory(remote))) {
                Ordering o = s.search(SemanticRequest.builder().query("entrega de mercadoria e pagamento combinado")
                        .build(remote)).get(0);
                assertEquals("remote", o.getModel().getSource());
                assertTrue(o.getModel().getSentTo().startsWith("http://127.0.0.1:"));
            }
            // another artifact is refused, naming both models
            try (SemanticSearcher s = new SemanticSearcher(c.moduleDir, c.items, local,
                    SyntheticCase.provider(new FakeEmbedder()))) {
                Availability a = s.availability(Modality.TEXT);
                assertFalse(a.isAvailable());
                assertEquals(Availability.Reason.MODEL_INCOMPATIBLE, a.getReason());
            }
        } finally {
            Files.deleteIfExists(credential);
        }
    }

    private static SemanticSearchConfig config(Path modelDir, String source, String endpoint, Path credential) {
        UTF8Properties p = new UTF8Properties();
        p.setProperty("textModelSource", source);
        p.setProperty("textLocalModel", modelDir.toString());
        p.setProperty("textRemoteProtocol", RemoteEmbedders.IPED_EMBED);
        p.setProperty("textRemoteEndpoint", endpoint);
        p.setProperty("textRemoteModel", "text");
        p.setProperty("textRemoteCredentialFile", credential.toString());
        p.setProperty("remoteMaxRetries", "1");
        SemanticSearchConfig c = new SemanticSearchConfig();
        c.processProperties(p);
        return c;
    }
}
