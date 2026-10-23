package iped.graph.neo4j;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for the CSV row padding {@link Neo4jImportRunner} applies before the Neo4j 5 bulk import:
 * rows shorter than the header (written before a property first appeared) must be completed with empty
 * values, everything else must be preserved byte by byte.
 */
public class Neo4jImportRunnerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private File write(String name, byte[] content) throws IOException {
        File file = new File(tmp.getRoot(), name);
        Files.write(file.toPath(), content);
        return file;
    }

    @Test
    public void padsShortRowsPreservingBytesAndLineTerminators() throws IOException {
        File header = write("nodes_X_headers_iped.csv", bytes("nodeId:ID,label:LABEL,userName:string\r\n"));
        String nul = "a\u0000b"; // control bytes inside a value must survive untouched
        File data = write("nodes_X_iped.csv", bytes("\"1\",\"X\",\"full\"\r\n" // complete row, CRLF
                + "\"2\",\"X\"\r\n" // short row, CRLF
                + "\"3\"\n" // short row, LF
                + "\"4\",\"with, comma\"\r\n" // comma inside quotes, short
                + "\"5\",\"" + nul + "\"")); // short, no terminator at end of file

        int padded = Neo4jImportRunner.padShortRows(header, data);

        assertEquals(4, padded);
        byte[] expected = bytes("\"1\",\"X\",\"full\"\r\n" //
                + "\"2\",\"X\",\"\"\r\n" //
                + "\"3\",\"\",\"\"\n" //
                + "\"4\",\"with, comma\",\"\"\r\n" //
                + "\"5\",\"" + nul + "\",\"\"");
        assertArrayEquals(expected, Files.readAllBytes(data.toPath()));
    }

    @Test
    public void leavesCompleteAndEmptyFilesUntouched() throws IOException {
        File header = write("relationships_e_headers_iped.csv", bytes("start:START_ID,end:END_ID,type:TYPE\r\n"));
        byte[] content = bytes("\"a\",\"b\",\"email\"\r\n\"c\",\"d\",\"email\"\r\n");
        File data = write("relationships_e_iped.csv", content);
        File empty = write("nodes_DOCUMENT_iped.csv", new byte[0]);

        assertEquals(0, Neo4jImportRunner.padShortRows(header, data));
        assertEquals(0, Neo4jImportRunner.padShortRows(header, empty));

        assertArrayEquals(content, Files.readAllBytes(data.toPath()));
        assertEquals(0, empty.length());
        assertEquals("no temporary file may be left behind", 0,
                tmp.getRoot().listFiles((dir, name) -> name.endsWith(".tmp")).length);
    }

    @Test
    public void resolvesHeaderAndDataFilesFromImportArguments() throws IOException {
        File sub = tmp.newFolder("0");
        write("0/nodes_P_headers_iped.csv", bytes("nodeId:ID,label:LABEL,userAccount:string\r\n"));
        write("0/nodes_P_iped.csv", bytes("\"1\",\"P\"\r\n\"2\",\"P\",\"acc\"\r\n"));
        write("0/relationships_e_headers_iped.csv", bytes("start:START_ID,end:END_ID,type:TYPE,relId:string\r\n"));
        write("0/relationships_e_iped.csv", bytes("\"1\",\"2\",\"email\"\r\n"));
        List<String> args = Arrays.asList("--input-encoding=utf-8", "--bad-tolerance=0",
                "--nodes=0/nodes_P_headers_iped.csv,0/nodes_P_iped.csv",
                "--relationships=0/relationships_e_headers_iped.csv,0/relationships_e_iped.csv", "--", "graph.db");

        assertEquals(2, Neo4jImportRunner.padShortRows(tmp.getRoot(), args));

        assertEquals("\"1\",\"P\",\"\"\r\n\"2\",\"P\",\"acc\"\r\n", text(new File(sub, "nodes_P_iped.csv")));
        assertEquals("\"1\",\"2\",\"email\",\"\"\r\n", text(new File(sub, "relationships_e_iped.csv")));
        assertEquals("headers are never modified", "nodeId:ID,label:LABEL,userAccount:string\r\n",
                text(new File(sub, "nodes_P_headers_iped.csv")));
    }

    @Test
    public void countsFieldsHonoringQuotes() {
        assertEquals(3, count("a,b,c"));
        assertEquals(2, count("\"x,y\",\"z\""));
        assertEquals(2, count("\"he said \"\"hi\"\", ok\",\"z\""));
        assertEquals(1, count("\"\""));
        assertEquals(0, count(""));
    }

    private static int count(String row) {
        byte[] b = bytes(row);
        return Neo4jImportRunner.countFields(b, b.length);
    }
}
