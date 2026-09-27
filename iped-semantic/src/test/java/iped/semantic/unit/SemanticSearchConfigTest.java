package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Test;

import iped.semantic.TestSupport;
import iped.semantic.config.SemanticConfigException;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.utils.UTF8Properties;

public class SemanticSearchConfigTest {

    private static Path distributedFile() {
        return TestSupport.distributedConfig().resolve("conf").resolve(SemanticSearchConfig.CONF_FILE);
    }

    private static Map<String, Object> getters(SemanticSearchConfig c) throws Exception {
        Map<String, Object> out = new TreeMap<>();
        for (Method m : SemanticSearchConfig.class.getMethods()) {
            if (m.getParameterCount() == 0 && m.getDeclaringClass() == SemanticSearchConfig.class
                    && (m.getName().startsWith("get") || m.getName().startsWith("is")) && !m.getName().equals("getConfiguration")) {
                out.put(m.getName(), m.invoke(c));
            }
        }
        for (Modality mod : Modality.values()) {
            out.put("source." + mod, c.getModelSource(mod));
            out.put("local." + mod, c.getLocalModel(mod));
            out.put("protocol." + mod, c.getRemoteProtocol(mod));
            out.put("endpoint." + mod, c.getRemoteEndpoint(mod));
            out.put("model." + mod, c.getRemoteModel(mod));
            out.put("credEnv." + mod, c.getRemoteCredentialEnv(mod));
            out.put("credFile." + mod, c.getRemoteCredentialFile(mod));
            out.put("categories." + mod, c.getCategories(mod));
        }
        return out;
    }

    @Test
    public void codeDefaultsEqualTheDistributedFile() throws Exception {
        SemanticSearchConfig defaults = new SemanticSearchConfig();
        SemanticSearchConfig fromFile = new SemanticSearchConfig();
        fromFile.processTaskConfig(distributedFile());

        Map<String, Object> a = getters(defaults);
        Map<String, Object> b = getters(fromFile);
        assertTrue("getters found", a.size() > 40);
        List<String> diffs = new ArrayList<>();
        for (String k : a.keySet()) {
            if (!String.valueOf(a.get(k)).equals(String.valueOf(b.get(k)))) {
                diffs.add(k + ": code=" + a.get(k) + " file=" + b.get(k));
            }
        }
        assertEquals("fallbacks must equal the distributed file", List.of(), diffs);
        fromFile.validate(Modality.TEXT);
        fromFile.validate(Modality.VISUAL);
    }

    @Test
    public void everyKeyOfTheFileIsReadByTheCode() throws Exception {
        UTF8Properties p = new UTF8Properties();
        p.load(distributedFile().toFile());
        SemanticSearchConfig c = new SemanticSearchConfig();
        for (String key : p.stringPropertyNames()) {
            UTF8Properties one = new UTF8Properties();
            one.setProperty(key, "not-a-valid-value-%%");
            c.processProperties(one);
            boolean reported;
            try {
                c.validate(Modality.TEXT);
                reported = false;
            } catch (SemanticConfigException e) {
                reported = true;
            }
            // String-valued keys accept anything; the others must reject garbage, which proves the
            // code reads them rather than ignoring an unknown key.
            boolean stringKey = key.endsWith("LocalModel") || key.endsWith("RemoteProtocol")
                    || key.endsWith("RemoteEndpoint") || key.endsWith("RemoteModel") || key.contains("Credential")
                    || key.endsWith("Categories") || key.equals("messageMediaTypes") || key.endsWith("Prefix");
            if (!stringKey && !reported) {
                fail("key " + key + " accepted garbage: is it read by SemanticSearchConfig?");
            }
        }
    }

    @Test
    public void aQuotedPrefixKeepsItsTrailingSpace() {
        UTF8Properties p = new UTF8Properties();
        p.setProperty("textRemoteQueryPrefix", " \"query: \" ");
        p.setProperty("textRemotePassagePrefix", "passage:");
        SemanticSearchConfig c = new SemanticSearchConfig();
        c.processProperties(p);
        assertEquals("query: ", c.getRemoteQueryPrefix());
        assertEquals("unquoted, trimmed as any value", "passage:", c.getRemotePassagePrefix());
    }

    @Test
    public void remoteSourceWithoutEndpointIsAConfigurationError() {
        UTF8Properties p = new UTF8Properties();
        p.setProperty("textModelSource", "remote");
        p.setProperty("textRemoteProtocol", "openai-embeddings");
        SemanticSearchConfig c = new SemanticSearchConfig();
        c.processProperties(p);
        try {
            c.validate(Modality.TEXT);
            fail("remote without endpoint must not validate");
        } catch (SemanticConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("textRemoteEndpoint"));
        }
        // the other modality is not affected
        try {
            c.validate(Modality.VISUAL);
        } catch (SemanticConfigException e) {
            fail("visual stays valid: " + e.getMessage());
        }
    }

    @Test
    public void visualRemoteCannotUseTheOpenAiFormat() {
        UTF8Properties p = new UTF8Properties();
        p.setProperty("visualModelSource", "remote");
        p.setProperty("visualRemoteProtocol", "openai-embeddings");
        p.setProperty("visualRemoteEndpoint", "https://lab.example/iped");
        SemanticSearchConfig c = new SemanticSearchConfig();
        c.processProperties(p);
        try {
            c.validate(Modality.VISUAL);
            fail("openai-embeddings has no images");
        } catch (SemanticConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("iped-embed/1"));
        }
    }

    @Test
    public void defaultDepthMustBeWithinOneAndTheCeiling() {
        for (String depth : new String[] { "0", "1001" }) {
            UTF8Properties p = new UTF8Properties();
            p.setProperty("defaultDepth", depth);
            SemanticSearchConfig c = new SemanticSearchConfig();
            c.processProperties(p);
            try {
                c.validate(Modality.TEXT);
                fail("defaultDepth " + depth + " must not validate");
            } catch (SemanticConfigException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("defaultDepth"));
            }
        }
    }

    @Test
    public void zeroMeansDerivedDefaults() {
        SemanticSearchConfig c = new SemanticSearchConfig();
        assertEquals(1000 * 4, c.getLexicalCandidates());
        assertTrue(c.getInferenceThreads() >= 1);
    }
}
