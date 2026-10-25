package iped.graph.neo4j;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.engine.graph.GraphFileWriter;
import iped.engine.graph.GraphLabel;
import iped.engine.graph.GraphRelationshipType;
import iped.engine.graph.GraphTask;

/**
 * Unit tests for the CSV row padding {@link Neo4jImportRunner} applies before the Neo4j 5 bulk import:
 * rows shorter than the header (written before a property first appeared) must be completed with empty
 * values, everything else must be preserved byte by byte.
 */
public class Neo4jImportRunnerTest {

    private static final String TMP_SUFFIX = ".padding.tmp";

    private static final String ARGS_FILE = GraphFileWriter.ARGS_FILE_NAME + "-iped.txt";

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

    private static String[] tempFiles(File dir) {
        return dir.list((parent, name) -> name.endsWith(TMP_SUFFIX));
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

    @Test
    public void completeFileIsOnlyRead() throws IOException {
        File header = write("nodes_X_headers_iped.csv", bytes("nodeId:ID,label:LABEL,name:string"));
        byte[] content = bytes("\"1\",\"X\",\"a\"\r\n\"2\",\"X\",\"b, \"\"c\"\"\"\r\n\r\n\"3\",\"X\",\"d\"");
        File data = write("nodes_X_iped.csv", content);
        // a folder takes the place of the temporary copy: creating the copy would fail
        File tmpPath = tmp.newFolder("nodes_X_iped.csv" + TMP_SUFFIX);
        Set<String> names = new TreeSet<>(Arrays.asList(tmp.getRoot().list()));
        long lastModified = data.lastModified();

        assertTrue(data.setReadOnly());
        try {
            assertEquals(0, Neo4jImportRunner.padShortRows(header, data));
        } finally {
            assertTrue(data.setWritable(true));
        }

        assertArrayEquals(content, Files.readAllBytes(data.toPath()));
        assertEquals(lastModified, data.lastModified());
        assertEquals("no file may be created or removed", names, new TreeSet<>(Arrays.asList(tmp.getRoot().list())));
        assertEquals(0, tmpPath.list().length);
    }

    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * Writes graph CSVs with {@link GraphFileWriter} where a PERSON property first appears after two
     * PERSON rows were already written and committed, which leaves those two rows shorter than the
     * final header.
     */
    private static GraphFileWriter writeCsvsWithLateProperty(File csvDir) throws Exception {
        GraphFileWriter writer = new GraphFileWriter(csvDir, "DOCUMENT");
        GraphLabel person = GraphLabel.of("PERSON");
        writer.writeNode(person, "phoneNumber", "+55 61 99999-0001", props("name", "Alice"));
        writer.writeNode(person, "phoneNumber", "+55 61 99999-0002", props("name", "Bob, \"Jr.\""));
        Map<String, Object> relProps = props(GraphTask.RELATIONSHIP_ID, 101);
        relProps.put(GraphTask.RELATIONSHIP_SOURCE, "ds-uuid-0001");
        writer.writeRelationship(person, "phoneNumber", "+55 61 99999-0001", person, "phoneNumber",
                "+55 61 99999-0002", GraphRelationshipType.of("call"), relProps);
        writer.flush();
        Map<String, Object> carol = props("name", "Carol");
        carol.put("userAccount", "carol@example.com");
        writer.writeNode(person, "phoneNumber", "+55 61 99999-0003", carol);
        writer.close();
        return writer;
    }

    /** Header and data files of each {@code --nodes=}/{@code --relationships=} option, header first. */
    private static List<File[]> importFiles(File csvDir, List<String> args) {
        List<File[]> options = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--nodes=") || arg.startsWith("--relationships=")) {
                String[] names = arg.substring(arg.indexOf('=') + 1).split(",");
                File[] files = new File[names.length];
                for (int i = 0; i < names.length; i++) {
                    files[i] = new File(csvDir, names[i]);
                }
                options.add(files);
            }
        }
        return options;
    }

    /**
     * Fields of a row in the dialect of {@link GraphFileWriter} (every value quoted, quotes inside values
     * replaced), counted independently of {@link Neo4jImportRunner#countFields(byte[], int)}.
     */
    private static int writerFields(String row) {
        assertTrue("unexpected row " + row, row.matches("\"[^\"]*\"(,\"[^\"]*\")*"));
        return row.split("\",\"", -1).length;
    }

    /** Rows of a data file written by {@link GraphFileWriter}, which ends every row with CRLF. */
    private static List<String> rows(File data) throws IOException {
        String text = text(data);
        assertTrue(text.isEmpty() || text.endsWith("\r\n"));
        List<String> rows = new ArrayList<>(Arrays.asList(text.split("\r\n", -1)));
        rows.remove(rows.size() - 1); // what follows the last CRLF
        return rows;
    }

    /**
     * Pads the CSVs listed in {@code args} and checks that every data row ends up with exactly the
     * field count of its header, short rows having received only empty trailing values.
     */
    private static void assertPaddedToHeaders(File csvDir, List<String> args, int expectedPadded) throws IOException {
        Map<File, List<String>> before = new LinkedHashMap<>();
        int shortRows = 0;
        for (File[] files : importFiles(csvDir, args)) {
            int columns = Files.readAllLines(files[0].toPath(), StandardCharsets.UTF_8).get(0).split(",").length;
            for (int i = 1; i < files.length; i++) {
                List<String> rows = rows(files[i]);
                before.put(files[i], rows);
                for (String row : rows) {
                    if (writerFields(row) < columns) {
                        shortRows++;
                    }
                }
            }
        }
        assertEquals("the writer must have left short rows", expectedPadded, shortRows);

        assertEquals(expectedPadded, Neo4jImportRunner.padShortRows(csvDir, args));

        for (File[] files : importFiles(csvDir, args)) {
            int columns = Files.readAllLines(files[0].toPath(), StandardCharsets.UTF_8).get(0).split(",").length;
            for (int i = 1; i < files.length; i++) {
                StringBuilder expected = new StringBuilder();
                for (String row : before.get(files[i])) {
                    expected.append(row);
                    for (int field = writerFields(row); field < columns; field++) {
                        expected.append(",\"\"");
                    }
                    expected.append("\r\n");
                }
                assertEquals(files[i].getName(), expected.toString(), text(files[i]));
                for (String row : rows(files[i])) {
                    assertEquals(row, columns, writerFields(row));
                }
                assertEquals(0, tempFiles(files[i].getParentFile()).length);
            }
        }
    }

    @Test
    public void padsRowsGraphFileWriterWroteBeforeANewProperty() throws Exception {
        File csvDir = tmp.newFolder("csv");
        writeCsvsWithLateProperty(csvDir);
        File relationships = new File(csvDir, "relationships_call_iped.csv");
        byte[] relationshipBytes = Files.readAllBytes(relationships.toPath());

        List<String> args = Files.readAllLines(new File(csvDir, ARGS_FILE).toPath(), StandardCharsets.UTF_8);
        assertPaddedToHeaders(csvDir, args, 2);

        assertArrayEquals("complete files are not rewritten", relationshipBytes,
                Files.readAllBytes(relationships.toPath()));
    }

    @Test
    public void padsMultiCaseCopiesOfGraphFileWriterCsvs() throws Exception {
        File caseCsvs = tmp.newFolder("case", "csv");
        writeCsvsWithLateProperty(caseCsvs).compressGeneratedCSVFiles();
        File prepared = tmp.newFolder("multicase", "csv");
        GraphFileWriter.prepareMultiCaseCSVs(prepared, Collections.singletonList(caseCsvs));

        List<String> args = new ArrayList<>();
        new Neo4jImportRunner(null, prepared.listFiles()).writeArgs(args, GraphTask.DB_NAME, false);
        assertTrue(args.toString(), args.contains("--nodes=0/nodes_PERSON_headers_iped.csv,0/nodes_PERSON_iped.csv"));
        assertPaddedToHeaders(prepared, args, 2);
    }

    @Test
    public void handlesLabelPrefixAndSeveralDataFilesPerOption() throws IOException {
        write("h1.csv", bytes("nodeId:ID,label:LABEL,name:string\r\n"));
        File d = write("d.csv", bytes("\"1\",\"PERSON\"\r\n"));
        write("h2.csv", bytes("nodeId:ID,label:LABEL,name:string,extra:string\r\n"));
        File d1 = write("d1.csv", bytes("\"2\",\"X\",\"a\"\r\n"));
        File d2 = write("d2.csv", bytes("\"3\"\r\n\"4\",\"X\",\"b\",\"c\"\r\n"));
        List<String> args = Arrays.asList("--nodes=PERSON:EXTRA=h1.csv,d.csv", "--nodes=h2.csv,d1.csv,d2.csv");

        assertEquals(3, Neo4jImportRunner.padShortRows(tmp.getRoot(), args));

        assertEquals("\"1\",\"PERSON\",\"\"\r\n", text(d));
        assertEquals("\"2\",\"X\",\"a\",\"\"\r\n", text(d1));
        assertEquals("\"3\",\"\",\"\",\"\"\r\n\"4\",\"X\",\"b\",\"c\"\r\n", text(d2));
    }

    @Test
    public void resolvesAbsolutePathsAndSkipsMissingFiles() throws IOException {
        File header = write("h.csv", bytes("a:ID,b:string\r\n"));
        File data = write("d.csv", bytes("\"1\"\r\n"));
        File workDir = tmp.newFolder("work");
        File missing = new File(tmp.getRoot(), "missing.csv");

        assertEquals(0, Neo4jImportRunner.padShortRows(missing, data));
        assertEquals(0, Neo4jImportRunner.padShortRows(header, missing));
        assertEquals(0, Neo4jImportRunner.padShortRows(workDir, Arrays.asList(
                "--nodes=missing.csv," + data.getAbsolutePath(), // no header file
                "--nodes=" + header.getAbsolutePath() + ",missing.csv"))); // no data file
        assertEquals("\"1\"\r\n", text(data));

        assertEquals(1, Neo4jImportRunner.padShortRows(workDir,
                Arrays.asList("--nodes=" + header.getAbsolutePath() + "," + data.getAbsolutePath())));
        assertEquals("\"1\",\"\"\r\n", text(data));
        assertFalse(missing.exists());
        assertEquals(0, workDir.list().length);
    }

    @Test
    public void leavesLongerRowsAndEmptyLinesUntouched() throws IOException {
        File header = write("h.csv", bytes("a:ID,b:string,c:string\r\n"));
        File data = write("d.csv", bytes("\"1\",\"x\",\"y\",\"extra\"\r\n" // longer than the header
                + "\r\n" // empty line, CRLF
                + "\"2\"\r\n" // short
                + "\n" // empty line, LF
                + "\"3\",\"x\",\"y\"\n" // complete
                + "\r\n"));

        assertEquals(1, Neo4jImportRunner.padShortRows(header, data));

        assertEquals("\"1\",\"x\",\"y\",\"extra\"\r\n" //
                + "\r\n" //
                + "\"2\",\"\",\"\"\r\n" //
                + "\n" //
                + "\"3\",\"x\",\"y\"\n" //
                + "\r\n", text(data));
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    public void padsRowsLongerThanTheReadBuffer() throws IOException {
        File header = write("h.csv", bytes("a:ID,b:string,c:string\r\n"));
        String value = repeat('v', 3 * Neo4jImportRunner.BUFFER_SIZE + 17);
        String complete = "\"1\",\"" + value + "\",\"z\"\r\n";
        File data = write("d.csv", bytes(complete + "\"2\",\"" + value + "\"\r\n" + "\"3\""));

        assertEquals(2, Neo4jImportRunner.padShortRows(header, data));

        assertEquals(complete + "\"2\",\"" + value + "\",\"\"\r\n" + "\"3\",\"\",\"\"", text(data));
    }

    @Test
    public void countsQuotedFieldsAcrossReadBufferBoundaries() throws IOException {
        File header = write("h.csv", bytes("a:ID,b:string,c:string,d:string\r\n"));
        // a single field holding commas and doubled quotes: its row has 2 fields, not 4
        String field = "\"say \"\"hi, you\"\", ok\"";
        String prefix = "\"1\",\"";
        String suffix = "\",\"q\",\"r\"\r\n\"2\",";
        for (int split = 0; split <= field.length(); split++) {
            // complete first row, then the second row up to its field, which starts 'split' bytes before
            // the end of the first read buffer (reads of a local file fill the buffer)
            int fieldStart = Neo4jImportRunner.BUFFER_SIZE - split;
            String head = prefix + repeat('p', fieldStart - prefix.length() - suffix.length()) + suffix;
            assertEquals(fieldStart, bytes(head).length);
            File data = write("d" + split + ".csv", bytes(head + field + "\r\n"));

            assertEquals(1, Neo4jImportRunner.padShortRows(header, data));

            assertEquals(head + field + ",\"\",\"\"\r\n", text(data));
        }
    }

    @Test
    public void readOnlyCheckStopsAtTheFirstShortRow() throws IOException {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read beyond the first short row");
            }
        };
        byte[] rows = bytes("\"1\",\"a\"\r\n\"2\"\r\n");
        InputStream in = new SequenceInputStream(new ByteArrayInputStream(rows), failing);

        assertTrue(Neo4jImportRunner.hasShortRow(in, 2, 4));
        assertFalse(Neo4jImportRunner.hasShortRow(new ByteArrayInputStream(rows), 1, 4));
    }

    /**
     * The byte-by-byte loop of the first implementation (commit 841f52128), kept as the reference that
     * the bulk implementation must match byte for byte.
     */
    private static int formerPadRows(InputStream in, OutputStream out, int columns) throws IOException {
        byte[] padding = bytes(",\"\"");
        ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
        int padded = 0;
        while (true) {
            int b = in.read();
            if (b != -1 && b != '\n') {
                line.write(b);
                continue;
            }
            byte[] bytes = line.toByteArray();
            int contentLength = bytes.length;
            if (contentLength > 0 && bytes[contentLength - 1] == '\r') {
                contentLength--;
            }
            out.write(bytes, 0, contentLength);
            if (contentLength > 0) {
                int fields = Neo4jImportRunner.countFields(bytes, contentLength);
                if (fields < columns) {
                    for (int i = fields; i < columns; i++) {
                        out.write(padding);
                    }
                    padded++;
                }
            }
            out.write(bytes, contentLength, bytes.length - contentLength);
            if (b == '\n') {
                out.write('\n');
            }
            line.reset();
            if (b == -1) {
                break;
            }
        }
        return padded;
    }

    private static void assertSameAsFormer(byte[] input, int columns, int... bufferSizes) throws IOException {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        int expectedPadded = formerPadRows(new ByteArrayInputStream(input), expected, columns);
        for (int bufferSize : bufferSizes) {
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            int padded = Neo4jImportRunner.padRows(new ByteArrayInputStream(input), actual, columns, bufferSize);
            boolean shortRow = Neo4jImportRunner.hasShortRow(new ByteArrayInputStream(input), columns, bufferSize);
            if (padded != expectedPadded || !Arrays.equals(expected.toByteArray(), actual.toByteArray())
                    || shortRow != (expectedPadded > 0)) {
                fail("input " + Arrays.toString(input) + ", columns " + columns + ", buffer " + bufferSize
                        + ": expected " + expectedPadded + " " + Arrays.toString(expected.toByteArray()) + ", got "
                        + padded + " " + Arrays.toString(actual.toByteArray()) + ", short row found: " + shortRow);
            }
        }
    }

    @Test
    public void matchesTheFormerByteByByteImplementation() throws IOException {
        byte[] symbols = { '"', ',', '\r', '\n', 'a', 0 };
        // every input of up to 6 bytes made of the bytes that matter to the CSV syntax
        for (int length = 0; length <= 6; length++) {
            int combinations = (int) Math.pow(symbols.length, length);
            for (int n = 0; n < combinations; n++) {
                byte[] input = new byte[length];
                for (int i = 0, rest = n; i < length; i++, rest /= symbols.length) {
                    input[i] = symbols[rest % symbols.length];
                }
                for (int columns = 1; columns <= 4; columns++) {
                    assertSameAsFormer(input, columns, 1, 2, 3, 7);
                }
            }
        }
        // a few rows of up to 3000 bytes, any byte value, the last one with or without a terminator
        Random random = new Random(20261024);
        for (int n = 0; n < 500; n++) {
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            int rows = random.nextInt(6);
            for (int row = 0; row < rows; row++) {
                int length = random.nextInt(random.nextBoolean() ? 20 : 3000);
                for (int i = 0; i < length; i++) {
                    input.write(random.nextInt(4) == 0 ? random.nextInt(256) : symbols[random.nextInt(symbols.length)]);
                }
                if (row < rows - 1 || random.nextBoolean()) {
                    input.write('\n');
                }
            }
            assertSameAsFormer(input.toByteArray(), 1 + random.nextInt(8), 1, 2, 5, 64, 1000,
                    Neo4jImportRunner.BUFFER_SIZE);
        }
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(content);
        }
        return bytes.toByteArray();
    }

    @Test
    public void readsOnlyTheImportArgumentFilesOfGraphFileWriter() throws IOException {
        File folder = tmp.newFolder("args");
        byte[] content = bytes("--nodes=nodes_P_headers_iped.csv,nodes_P_iped.csv\r\n"
                + "--relationships=relationships_e_headers_iped.csv,relationships_e_iped.csv\r\n");
        Files.write(new File(folder, ARGS_FILE).toPath(), content);
        // not written by GraphFileWriter: the plain name, a compressed copy, copies made by hand, no suffix
        Files.write(new File(folder, GraphFileWriter.ARGS_FILE_NAME + ".txt").toPath(),
                bytes("--nodes=legacy_headers.csv,legacy.csv\r\n"));
        Files.write(new File(folder, ARGS_FILE + ".gzip").toPath(), gzip(content));
        Files.write(new File(folder, GraphFileWriter.ARGS_FILE_NAME + "-iped - Copy.txt").toPath(), content);
        Files.write(new File(folder, GraphFileWriter.ARGS_FILE_NAME + "-iped (1).txt").toPath(), content);
        Files.write(new File(folder, GraphFileWriter.ARGS_FILE_NAME + "-.txt").toPath(), content);
        File other = tmp.newFolder("args2");
        Files.write(new File(other, GraphFileWriter.ARGS_FILE_NAME + "-case_2.txt").toPath(),
                bytes("--nodes=1/h.csv,1/d.csv\r\n"));

        List<String> args = new ArrayList<>();
        new Neo4jImportRunner(null, folder, other).writeArgs(args, GraphTask.DB_NAME, false);

        assertEquals(Arrays.asList("--input-encoding=utf-8", "--bad-tolerance=0", "--ignore-empty-strings=true",
                "--skip-duplicate-nodes=true", "--nodes=nodes_P_headers_iped.csv,nodes_P_iped.csv",
                "--relationships=relationships_e_headers_iped.csv,relationships_e_iped.csv", "--nodes=1/h.csv,1/d.csv",
                "--", GraphTask.DB_NAME), args);
    }

    @Test
    public void removesTheTemporaryPathWhenTheCopyCannotBeCreated() throws IOException {
        File header = write("h.csv", bytes("a:ID,b:string\r\n"));
        byte[] content = bytes("\"1\"\r\n");
        File data = write("d.csv", content);
        // an empty folder takes the place of the temporary copy: creating the copy fails
        File tmpPath = tmp.newFolder("d.csv" + TMP_SUFFIX);

        assertThrows(FileNotFoundException.class, () -> Neo4jImportRunner.padShortRows(header, data));

        assertArrayEquals(content, Files.readAllBytes(data.toPath()));
        assertFalse("the temporary path must be removed after a failure", tmpPath.exists());
    }

    @Test
    public void aFailedCleanupDoesNotMaskTheOriginalError() throws IOException {
        File header = write("h.csv", bytes("a:ID,b:string\r\n"));
        byte[] content = bytes("\"1\"\r\n");
        File data = write("d.csv", content);
        // a folder that cannot be removed takes the place of the temporary copy
        File tmpPath = tmp.newFolder("d.csv" + TMP_SUFFIX);
        write("d.csv" + TMP_SUFFIX + "/keep", bytes("x"));

        FileNotFoundException e = assertThrows(FileNotFoundException.class,
                () -> Neo4jImportRunner.padShortRows(header, data));

        assertTrue(e.getMessage(), e.getMessage().contains(tmpPath.getName()));
        assertArrayEquals(content, Files.readAllBytes(data.toPath()));
        assertTrue(tmpPath.isDirectory());
    }

    @Test
    public void removesTheTemporaryCopyWhenTheDataFileCannotBeReplaced() throws IOException {
        // Java opens files on Windows without FILE_SHARE_DELETE: an open data file cannot be replaced
        assumeTrue("needs the file sharing rules of Windows",
                System.getProperty("os.name", "").toLowerCase().startsWith("windows"));
        File header = write("h.csv", bytes("a:ID,b:string\r\n"));
        byte[] content = bytes("\"1\"\r\n");
        File data = write("d.csv", content);

        try (InputStream open = new FileInputStream(data)) {
            assertThrows(IOException.class, () -> Neo4jImportRunner.padShortRows(header, data));
        }

        assertArrayEquals(content, Files.readAllBytes(data.toPath()));
        assertEquals(0, tempFiles(tmp.getRoot()).length);
    }

    @Test
    public void logsEachPaddedFileOnce() throws IOException {
        File header = write("nodes_X_headers_iped.csv", bytes("nodeId:ID,label:LABEL,userName:string\r\n"));
        File data = write("nodes_X_iped.csv", bytes("\"1\",\"X\"\r\n\"2\",\"X\",\"full\"\r\n\"3\"\r\n"));
        List<String> messages = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender("padding-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                messages.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager
                .getLogger(Neo4jImportRunner.class);
        Level level = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            assertEquals(2, Neo4jImportRunner.padShortRows(header, data));
            // already complete: nothing to log
            assertEquals(0, Neo4jImportRunner.padShortRows(header, data));
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(level);
            appender.stop();
        }

        // the validation of a processed case searches the log for this line (quickstart, step 19)
        assertEquals(Collections.singletonList(
                "INFO Padded 2 row(s) of nodes_X_iped.csv to 3 columns, as required by the Neo4j 5 importer."), messages);
    }
}
