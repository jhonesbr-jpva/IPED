package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.Diagnostics;
import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.protocol.JsonRpcCodec;

/**
 * Under the stdio transport, stdout carries JSON-RPC and nothing else, as read by a client exactly as
 * strict as the transport allows: every line is a message.
 *
 * <p>
 * <b>Found in the field.</b> A client that parsed each stdout line as a message failed on
 * {@code iped_open_case}. Ahead of the response, stdout carried two lines that the Sleuthkit bindings
 * print with {@code System.out.println} when they load their native library — {@code Temp Folder for
 * Libraries: ...} and {@code SleuthkitJNI: loaded libtsk_jni}. The server's own code never wrote to
 * stdout. A dependency did, mid-session, the first time a case built from a disk image was opened.
 *
 * <p>
 * The server runs in a process of its own, launched the way the installation guides publish it,
 * because that is the only place the real stdout can be observed: in-process, {@code System.out} is
 * the test's. It is also the only place the defect reliably shows. The library prints once per
 * process, so a JVM where an earlier suite already loaded it prints nothing and passes for the wrong
 * reason.
 *
 * <p>
 * Only a case built from a disk image loads Sleuthkit, so what this exercises depends on the
 * reference case it is given. The client's rule does not, and the test says on stderr whether the
 * library was loaded.
 */
public class StrictStdioClientTest {

    /** What the Sleuthkit bindings print once the native library is in. */
    private static final String SLEUTHKIT_LOADED = "SleuthkitJNI: loaded";

    /** Room for opening a case of a few hundred thousand items, with a wide margin. */
    private static final long SERVER_LIMIT_SECONDS = 300;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void stdoutCarriesNothingButTheProtocolThroughACaseOpen() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        File ipedRoot = Diagnostics.resolveIpedRoot();

        Process server = launch(ipedRoot);
        StringBuffer stderr = drain(server.getErrorStream());
        try {
            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
            Writer stdin = new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8);

            send(stdin, request(1, "initialize", JsonRpcCodec.mapper().createObjectNode()));
            assertFalse(awaitResponse(stdout, 1, stderr).has("error"));
            send(stdin, notification("notifications/initialized"));

            ObjectNode call = JsonRpcCodec.mapper().createObjectNode();
            call.put("name", "iped_open_case");
            call.set("arguments", JsonRpcCodec.mapper().valueToTree(
                    Collections.singletonMap("case_path", caseDir.getAbsolutePath())));
            send(stdin, request(2, "tools/call", call));
            JsonNode opened = awaitResponse(stdout, 2, stderr);
            assertFalse("the case must open for the test to have exercised anything: " + opened,
                    opened.has("error"));

            // End of input is how every harness closes a session. Whatever the server writes on the
            // way out is held to the same rule.
            stdin.close();
            String line;
            while ((line = stdout.readLine()) != null) {
                requireMessage(line, stderr);
            }
            assertTrue("the server must exit once its input closes", server.waitFor(60, TimeUnit.SECONDS));
        } finally {
            server.destroyForcibly();
        }

        // Not an assertion — whether the library loads is a property of the case, not of the server.
        // It says whether this run reached the path the defect was found on.
        System.err.println("[" + getClass().getSimpleName() + "] Sleuthkit native library loaded by the server: "
                + (stderr.indexOf(SLEUTHKIT_LOADED) >= 0 ? "yes, and its output went to stderr"
                        : "no — the reference case was not built from a disk image"));
    }

    /**
     * The server's command line, as the guides publish it, on this test's classpath.
     *
     * <p>
     * The classpath goes in an argument file: the test classpath of this module runs to hundreds of
     * entries, past what Windows accepts on a command line.
     */
    private Process launch(File ipedRoot) throws IOException {
        File installation = configurationOf(ipedRoot);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        File argFile = temp.newFile("server.args");
        // Forward slashes, because a backslash inside a quoted argument-file entry is an escape.
        Files.write(argFile.toPath(), Collections.singletonList("-cp \"" + classpath.replace('\\', '/') + "\""),
                StandardCharsets.UTF_8);

        String java = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder(java,
                "-Dlog4j.configurationFile="
                        + new File(installation, "conf/Log4j2ConfigurationMcp.xml").getAbsolutePath(),
                "-D" + Diagnostics.IPED_ROOT_PROPERTY + "=" + installation.getAbsolutePath(),
                // The audit buffer and the export folder default to the user's home; this keeps both
                // out of the examiner's.
                "-Duser.home=" + temp.newFolder("home").getAbsolutePath(),
                "@" + argFile.getAbsolutePath(), McpServerMain.class.getName());
        Process server = builder.start();

        // A watchdog rather than a JUnit timeout: JUnit abandons a thread blocked on the pipe and
        // leaves the server running, while stopping the server closes the pipe and ends the read.
        Thread watchdog = new Thread(() -> {
            try {
                if (!server.waitFor(SERVER_LIMIT_SECONDS, TimeUnit.SECONDS)) {
                    server.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                // The test is over.
            }
        }, "strict-client-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return server;
    }

    /**
     * The installation's engine configuration, with the MCP server's own settings at the defaults.
     *
     * <p>
     * The server reads its transport from {@code conf/McpServerConfig.txt} of the installation it
     * runs from, and a bench installation is configured for whatever it is used for — the socket
     * transport, a port already taken by a running server, processing enabled. This test is about
     * stdio, read-only, as the report that found the defect used it. So the configuration is copied
     * and that one file is written here.
     *
     * <p>
     * <b>Copied, never linked.</b> A directory junction to the real installation, under a temporary
     * folder, would have the installation emptied by the recursive delete that cleans the folder up.
     * The runtime, libraries, models and the other tools stay out: the classpath is this test's, and
     * none of them is read to open a case. {@code tools/tsk} is the exception, a few megabytes the
     * engine loads the Sleuthkit's native dependencies from while its configuration loads.
     */
    private File configurationOf(File ipedRoot) throws IOException {
        File installation = temp.newFolder("installation");
        for (String file : new String[] { "IPEDConfig.txt", "LocalConfig.txt" }) {
            File source = new File(ipedRoot, file);
            if (source.isFile()) {
                Files.copy(source.toPath(), new File(installation, file).toPath());
            }
        }
        for (String folder : new String[] { "conf", "localization", "profiles", "scripts", "tools/tsk" }) {
            File source = new File(ipedRoot, folder);
            if (source.isDirectory()) {
                copyTree(source.toPath(), new File(installation, folder).toPath());
            }
        }
        Files.write(new File(installation, "conf/McpServerConfig.txt").toPath(),
                Arrays.asList("transport = stdio", "accessMode = READ_ONLY", "processingEnabled = false"),
                StandardCharsets.UTF_8);
        return installation;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Reads until the response with this id, refusing anything on the way that is not a message. */
    private static JsonNode awaitResponse(BufferedReader stdout, int id, StringBuffer stderr) throws IOException {
        String line;
        while ((line = stdout.readLine()) != null) {
            JsonNode message = requireMessage(line, stderr);
            if (message.path("id").asInt(-1) == id) {
                return message;
            }
        }
        fail("stdout closed before the response to request " + id + " arrived — the server exited, or ran past "
                + SERVER_LIMIT_SECONDS + " s and was stopped. Server stderr:\n" + stderr);
        return null;
    }

    /** The rule under test: a line on stdout is a JSON-RPC 2.0 message, or the channel is corrupt. */
    private static JsonNode requireMessage(String line, StringBuffer stderr) {
        JsonNode message;
        try {
            message = JsonRpcCodec.mapper().readTree(line);
        } catch (IOException e) {
            message = null;
        }
        if (message == null || !message.isObject()) {
            throw new AssertionError("stdout carried a line that is not a JSON-RPC message, so a strict client "
                    + "would stop here: [" + line + "]. Server stderr:\n" + stderr);
        }
        assertEquals("every message on stdout must declare JSON-RPC 2.0: " + line, "2.0",
                message.path("jsonrpc").asText());
        return message;
    }

    private static ObjectNode request(int id, String method, JsonNode params) {
        ObjectNode message = notification(method);
        message.put("id", id);
        message.set("params", params);
        return message;
    }

    private static ObjectNode notification(String method) {
        ObjectNode message = JsonRpcCodec.mapper().createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        return message;
    }

    private static void send(Writer stdin, JsonNode message) throws IOException {
        assertNotNull(message);
        stdin.write(JsonRpcCodec.mapper().writeValueAsString(message) + "\n");
        stdin.flush();
    }

    /** Collects stderr on its own thread, so a chatty server never blocks on a full pipe. */
    private static StringBuffer drain(InputStream stream) {
        StringBuffer collected = new StringBuffer();
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    collected.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // The process is gone; what was collected is what there is.
            }
        }, "strict-client-stderr");
        reader.setDaemon(true);
        reader.start();
        return collected;
    }
}
