package iped.engine.graph;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Golden test of the graph CSV files written by {@link GraphFileWriter} (forensic determinism,
 * feature 010): the files under {@code iped/engine/graph/golden/} were written by the writer that
 * used the Neo4j {@code Label}/{@code RelationshipType} API (before feature 010) from the very same
 * dataset as {@link #writeDataset(GraphFileWriter)}. File names and contents must stay byte for byte
 * identical. Only the names of the internal {@code *_fieldData} files are checked.
 */
public class GraphFileWriterTest {

    private static final String FIELD_DATA_SUFFIX = "_fieldData";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static GraphLabel L(String name) {
        return GraphLabel.of(name);
    }

    private static GraphRelationshipType T(String name) {
        return GraphRelationshipType.of(name);
    }

    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, Object> rel(int relId, String dataSource) {
        Map<String, Object> map = new HashMap<>();
        map.put(GraphTask.RELATIONSHIP_ID, relId);
        map.put(GraphTask.RELATIONSHIP_SOURCE, dataSource);
        return map;
    }

    /**
     * Must stay identical to the dataset used to capture the golden files: accents, commas, quotes,
     * backslash and line breaks in values, labels with '_', a node with two labels, merged (replaced)
     * nodes, a duplicate record and relationships with and without properties.
     */
    private static void writeDataset(GraphFileWriter w) throws IOException {
        Map<String, Object> relProps1 = rel(101, "ds-uuid-0001");
        Map<String, Object> relProps2 = rel(202, "ds-uuid-0002");

        w.writeNode(L("DATASOURCE"), "evidenceUUID", "ds-uuid-0001", props("name", "Evidência nº 1, cópia"));

        Map<String, Object> person = props("name", new ArrayList<>(Arrays.asList("José da Silva, Jr.", "Zé")));
        person.put("phoneNumber", new TreeSet<>(Arrays.asList("+55 61 99999-0001", "+55 61 3333-0001")));
        person.put("emailAddress", new TreeSet<>(Arrays.asList("jose@example.com")));
        String personId = w.writeNode(L("PERSON"), "phoneNumber", "+55 61 99999-0001", person);

        w.writeNodeReplace(L("PERSON"), "emailAddress", "jose@example.com", personId);
        w.writeNodeReplace(L("EMAIL"), "emailAddress", "jose@example.com", personId);
        w.writeNodeReplace(L("PHONE"), "phoneNumber", "+55 61 3333-0001", personId);

        w.writeNode(L("EMAIL"), "emailAddress", "acao@exemplo.com.br",
                props("name", "Ação \"Especial\" C:\\dados\r\nsegunda linha"));
        w.writeNode(L("EMAIL"), "emailAddress", "acao@exemplo.com.br",
                props("name", "Ação \"Especial\" C:\\dados\r\nsegunda linha"));
        w.writeNode(L("EMAIL"), "emailAddress", "jose@example.com");

        w.writeNode(L("PHONE"), "phoneNumber", "+55 61 3333-0001");

        w.writeNode(L("MAC_ADDRESS"), "mac_address", "00:11:22:33:44:55", props("SSID", "Rede_Casa"));

        w.writeCreateNode("entity", "acme", props("name", "ACME Ltda."), L("ORGANIZATION"), L("CLIENT"));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("path", "evidence/mail/msg1.eml");
        doc.put("name", "msg1.eml");
        doc.put("categories", "Emails");
        doc.put("hash", "d41d8cd98f00b204e9800998ecf8427e");
        w.writeNode(L("DOCUMENT"), "evidenceId", "42", doc);

        w.writeRelationship(L("PERSON"), "phoneNumber", "+55 61 99999-0001", L("EMAIL"), "emailAddress",
                "acao@exemplo.com.br", T("email"), relProps1);
        w.writeRelationship(L("EMAIL"), "emailAddress", "jose@example.com", L("PHONE"), "phoneNumber",
                "+55 61 3333-0001", T("message"), relProps2);
        w.writeRelationship(personId, L("MAC_ADDRESS"), "mac_address", "00:11:22:33:44:55", T("wirelessnetwork"),
                relProps1);
        w.writeCreateRelationship(L("DATASOURCE"), "evidenceUUID", "ds-uuid-0001", L("PERSON"), "phoneNumber",
                "+55 61 99999-0001", T("contact"));
        w.writeCreateRelationship(personId, L("ORGANIZATION"), "entity", "acme", T("near_hit"));
    }

    private static Set<String> fileNames(File dir) {
        Set<String> names = new TreeSet<>();
        for (File file : dir.listFiles()) {
            if (!file.getName().startsWith(".")) {
                names.add(file.getName());
            }
        }
        return names;
    }

    @Test
    public void csv_files_match_the_pre_010_golden_files() throws Exception {
        File output = tmp.newFolder("csv");
        GraphFileWriter writer = new GraphFileWriter(output, "DOCUMENT");
        writeDataset(writer);
        writer.close();

        Path golden = Paths.get(GraphFileWriterTest.class.getResource("golden").toURI());
        Set<String> goldenNames = fileNames(golden.toFile());

        Set<String> expectedNames = new TreeSet<>(goldenNames);
        for (String name : goldenNames) {
            if (name.matches("(nodes|relationships)_.+_iped\\.csv") && !name.contains("_headers_")) {
                expectedNames.add(name + FIELD_DATA_SUFFIX);
            }
        }
        assertEquals(expectedNames, fileNames(output));

        for (String name : goldenNames) {
            byte[] expected = Files.readAllBytes(golden.resolve(name));
            byte[] actual = Files.readAllBytes(new File(output, name).toPath());
            assertArrayEquals("content of " + name, expected, actual);
        }
    }

    @Test
    public void labels_and_relationship_types_compare_by_name() {
        assertEquals(GraphLabel.of("PERSON"), GraphLabel.of("PERSON"));
        assertEquals(GraphLabel.of("PERSON").hashCode(), GraphLabel.of("PERSON").hashCode());
        assertEquals("PERSON", GraphLabel.of("PERSON").name());
        assertEquals("PERSON", GraphLabel.of("PERSON").toString());
        assertEquals(GraphRelationshipType.of("email"), GraphRelationshipType.of("email"));
        assertEquals("email", GraphRelationshipType.of("email").name());
    }

}
