package iped.mcp.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.session.CasePool;
import iped.mcp.session.WriteClaims;

/**
 * Once stdout is claimed for the protocol, a library printing to {@code System.out} cannot reach it.
 *
 * <p>
 * <b>Found in the field.</b> The Sleuthkit bindings print two lines to {@code System.out} when they
 * load their native library, on the first open of a case built from a disk image. Under the stdio
 * transport those lines landed on the protocol channel ahead of the response, and a client that
 * reads every line as a message failed. The process-level check, against a real case, is
 * {@code StrictStdioClientTest}. This one runs without a case and pins the two halves of the fix: the
 * claim itself, and a transport that writes to the stream it was given rather than to
 * {@code System.out}.
 */
public class ProtocolStdoutTest {

    private static final String[] SLEUTHKIT_LINES = { "Temp Folder for Libraries: C:\\Temp",
            "SleuthkitJNI: loaded libtsk_jni" };

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private PrintStream originalOut;
    private PrintStream originalErr;

    /** What the process would see on file descriptors 1 and 2. */
    private ByteArrayOutputStream processStdout;
    private ByteArrayOutputStream processStderr;

    @Before
    public void standInForTheProcessStreams() throws IOException {
        originalOut = System.out;
        originalErr = System.err;
        processStdout = new ByteArrayOutputStream();
        processStderr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(processStdout, true, StandardCharsets.UTF_8.name()));
        System.setErr(new PrintStream(processStderr, true, StandardCharsets.UTF_8.name()));
    }

    @After
    public void restoreTheProcessStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    public void theClaimKeepsStdoutForTheProtocolAndSendsEveryOtherWriteToStderr() throws IOException {
        PrintStream stdoutBefore = System.out;

        OutputStream protocol = ProtocolStdout.claim();

        assertSame("the claimed stream must be the one that was stdout", stdoutBefore, protocol);
        assertSame("after the claim, System.out must be stderr", System.err, System.out);

        System.out.println(SLEUTHKIT_LINES[1]);
        protocol.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n".getBytes(StandardCharsets.UTF_8));
        protocol.flush();

        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n", text(processStdout));
        assertTrue("the stray print must end up on stderr: " + text(processStderr),
                text(processStderr).contains(SLEUTHKIT_LINES[1]));
    }

    @Test
    public void aLibraryPrintingMidSessionDoesNotReachTheChannel() throws Exception {
        OutputStream protocol = ProtocolStdout.claim();
        InputStream harness = new OneMessagePerRead(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n");

        try (CasePool casePool = new CasePool();
                StdioTransport transport = new StdioTransport(McpTestSupport.configWithTempAudit(temp.getRoot()),
                        casePool, new WriteClaims(), harness, protocol)) {
            transport.serve();
        }

        String[] lines = text(processStdout).trim().split("\\r?\\n");
        assertEquals("stdout must carry the two responses and nothing else: " + text(processStdout), 2,
                lines.length);
        for (int i = 0; i < lines.length; i++) {
            JsonNode message = JsonRpcCodec.mapper().readTree(lines[i]);
            assertEquals("2.0", message.path("jsonrpc").asText());
            assertEquals(i + 1, message.path("id").asInt());
        }
        for (String line : SLEUTHKIT_LINES) {
            assertTrue("the library's output must end up on stderr: " + text(processStderr),
                    text(processStderr).contains(line));
        }
    }

    private static String text(ByteArrayOutputStream stream) {
        return new String(stream.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * A harness that sends one message per read, with the library printing just before the second.
     *
     * <p>
     * One message per read is what makes the print land mid-session: the server has read, answered
     * and flushed the first message before it asks for the second, so the print falls between two
     * responses, where the field saw it.
     */
    private static final class OneMessagePerRead extends InputStream {

        private final byte[][] messages;
        private int message;
        private int offset;

        OneMessagePerRead(String... messages) {
            this.messages = new byte[messages.length][];
            for (int i = 0; i < messages.length; i++) {
                this.messages[i] = messages[i].getBytes(StandardCharsets.UTF_8);
            }
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int off, int len) {
            if (message < messages.length && offset == messages[message].length) {
                message++;
                offset = 0;
            }
            if (message >= messages.length) {
                return -1;
            }
            if (message == 1 && offset == 0) {
                // What LibraryUtils does as the native library loads.
                for (String line : SLEUTHKIT_LINES) {
                    System.out.println(line);
                }
            }
            int count = Math.min(len, messages[message].length - offset);
            System.arraycopy(messages[message], offset, buffer, off, count);
            offset += count;
            return count;
        }
    }
}
