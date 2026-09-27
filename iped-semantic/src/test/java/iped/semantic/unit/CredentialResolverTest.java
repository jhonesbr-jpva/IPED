package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.CredentialResolver;
import iped.semantic.model.CredentialResolver.Credential;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.RemoteEmbedders;
import iped.semantic.store.BuildRecord;
import iped.utils.UTF8Properties;

/**
 * FR-045: the configuration names where the credential is, never the value; the value reaches the
 * {@code Authorization} header and nothing else - not the log, not the build record, not a
 * {@code toString()}.
 */
public class CredentialResolverTest {

    private static final String SECRET = "tk-9f8e7d6c5b4a-SECRET";

    @Test
    public void fromAFileOrAnEnvironmentVariableTheFileFirst() throws Exception {
        Path f = Files.createTempFile("cred", ".txt");
        try {
            Files.write(f, ("  " + SECRET + "\r\n").getBytes(StandardCharsets.UTF_8));
            Map<String, String> env = Map.of("IPED_X", "from-env");
            Credential c = CredentialResolver.resolve(f.toString(), "IPED_X", env::get);
            assertTrue(c.isPresent());
            assertTrue("the file wins: the variable always has a default name", c.origin().startsWith("file "));

            Credential e = CredentialResolver.resolve("", "IPED_X", env::get);
            assertEquals("environment variable IPED_X", e.origin());

            Credential none = CredentialResolver.resolve("", "IPED_UNSET", env::get);
            assertFalse(none.isPresent());
            assertEquals("none", none.origin());

            for (Credential x : new Credential[] { c, e, none }) {
                assertFalse("toString never shows the value", x.toString().contains(SECRET));
                assertFalse(x.toString().contains("from-env"));
            }
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    public void anEmptyOrMissingFileIsAConfigurationError() throws Exception {
        Path f = Files.createTempFile("cred", ".txt");
        try {
            try {
                CredentialResolver.resolve(f.toString(), "", k -> null);
                fail("empty file");
            } catch (EmbeddingException ex) {
                assertTrue(ex.isConfiguration());
            }
        } finally {
            Files.deleteIfExists(f);
        }
        try {
            CredentialResolver.resolve(f.toString(), "", k -> null);
            fail("missing file");
        } catch (EmbeddingException ex) {
            assertTrue(ex.isConfiguration());
        }
    }

    @Test
    public void theValueGoesInTheHeaderAndNeverInTheLogOrTheRecord() throws Exception {
        Path f = Files.createTempFile("cred", ".txt");
        Files.write(f, SECRET.getBytes(StandardCharsets.UTF_8));
        StringWriter log = new StringWriter();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration conf = ctx.getConfiguration();
        WriterAppender appender = WriterAppender.createAppender(PatternLayout.newBuilder().withPattern("%m%n").build(),
                null, log, "credential-capture", false, true);
        appender.start();
        LoggerConfig semantic = new LoggerConfig("iped.semantic", Level.ALL, true);
        semantic.addAppender(appender, Level.ALL, null);
        conf.addLogger("iped.semantic", semantic);
        ctx.updateLoggers();
        String[] auth = new String[1];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            auth[0] = ex.getRequestHeaders().getFirst("Authorization");
            // the service accepts the opening request and refuses the next, so the error path runs too
            boolean refuse = calls[0]++ > 0;
            byte[] out = "{\"model\":\"m\",\"data\":[{\"index\":0,\"embedding\":[1,0]}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(refuse ? 401 : 200, refuse ? -1 : out.length);
            try (OutputStream os = ex.getResponseBody()) {
                if (!refuse) {
                    os.write(out);
                }
            }
        });
        server.start();
        try {
            UTF8Properties p = new UTF8Properties();
            p.setProperty("textModelSource", "remote");
            p.setProperty("textRemoteProtocol", RemoteEmbedders.OPENAI);
            p.setProperty("textRemoteEndpoint", "http://127.0.0.1:" + server.getAddress().getPort());
            p.setProperty("textRemoteModel", "m");
            p.setProperty("textRemoteCredentialFile", f.toString());
            p.setProperty("remoteMaxRetries", "0");
            SemanticSearchConfig config = new SemanticSearchConfig();
            config.processProperties(p);
            Embedder e = RemoteEmbedders.open(config, Modality.TEXT, 10);
            assertEquals("the header carries it", "Bearer " + SECRET, auth[0]);
            try {
                e.embedTexts(Embedder.Role.PASSAGE, java.util.List.of("x"));
                fail("401");
            } catch (EmbeddingException ex) {
                assertFalse(ex.getMessage().contains(SECRET));
            }
            assertFalse(e.toString().contains(SECRET));

            BuildRecord.ModalityRecord mr = new BuildRecord.ModalityRecord();
            mr.remote = new BuildRecord.RemoteRecord(RemoteEmbedders.OPENAI,
                    RemoteEmbedders.safeDestination(config.getRemoteEndpoint(Modality.TEXT)));
            BuildRecord r = new BuildRecord();
            r.modalities.put("text", mr);
            assertFalse("never in the build record", r.toJson().contains(SECRET));

            String captured = log.toString();
            assertTrue("the log says where the credential came from: " + captured, captured.contains("credential file"));
            assertFalse("and never what it is", captured.contains(SECRET));
        } finally {
            server.stop(0);
            conf.removeLogger("iped.semantic");
            ctx.updateLoggers();
            appender.stop();
            Files.deleteIfExists(f);
        }
    }

    private final int[] calls = { 0 };
}
