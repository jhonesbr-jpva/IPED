package iped.semantic;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

import iped.engine.lucene.analysis.AppAnalyzer;
import iped.semantic.config.SemanticConfigException;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.Source;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbedderProvider;
import iped.semantic.model.EmbeddingException;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.task.SourceValidator;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;
import iped.semantic.task.VisualItem;
import iped.semantic.task.VisualPreparation;

/**
 * A case built in the test JVM: the text preparation of the processing, driven directly with a
 * {@link FakeEmbedder}, over a synthetic set of items. It exercises the same code the task runs,
 * without the IPED pipeline, so coverage, determinism, resume and append can be checked fast.
 */
public final class SyntheticCase {

    public static final String HEADER = "METADATA:";

    public final File moduleDir;
    public final BitSet items = new BitSet();
    public final SemanticSearchConfig config;

    public SyntheticCase(String name, SemanticSearchConfig config) {
        this.moduleDir = TestSupport.tempDir(name).resolve("iped").toFile();
        this.moduleDir.mkdirs();
        this.config = config;
    }

    public SyntheticCase(String name) {
        this(name, new SemanticSearchConfig());
    }

    /** Provider serving one embedder as the configured local source. */
    public static EmbedderProvider provider(Embedder e) {
        return new EmbedderProvider() {
            @Override
            public Embedder open(Modality m) {
                return e;
            }

            @Override
            public Source source(Modality m) {
                return Source.LOCAL;
            }

            @Override
            public String destination(Modality m) {
                return null;
            }

            @Override
            public String describeLocation(Modality m) {
                return "test embedder " + e.identity().getId();
            }
        };
    }

    /** Opens a processing of this case with an embedder (a new run, --continue or --append). */
    public TextPreparation open(Embedder e, Integer capabilityStartId) throws Exception {
        TestSupport.ensureConfiguration();
        SourceValidator.Validated v;
        try {
            v = SourceValidator.validate(config, Modality.TEXT, SemanticSearchConfig.ENABLE_TEXT, provider(e));
        } catch (SemanticConfigException ex) {
            throw new IllegalStateException(ex);
        }
        TextPreparation.Settings s = new TextPreparation.Settings().analyzer(AppAnalyzer.get()).metadataHeader(HEADER)
                .ocrEnabled(false).transcriptionEnabled(false).capabilityStartId(capabilityStartId)
                .ipedVersion("test");
        return TextPreparation.open(moduleDir, config, v, s);
    }

    /** A document item; registered as present in the main index. */
    public TextItem doc(int id, String text) {
        items.set(id);
        return TextItem.document(id, "text/plain", Set.of("Documentos"),
                () -> new StringReader(text + "\n" + HEADER + "\nk: v\n"), null);
    }

    /** An item without text of a given type. */
    public TextItem noText(int id, String mediaType) {
        items.set(id);
        return TextItem.document(id, mediaType, Set.of("Outros"), null, null);
    }

    /** A chat message; the chat item itself must be registered with {@link #doc}. */
    public TextItem message(int id, int chatId, long minute, String sender, String body) {
        items.set(id);
        return TextItem.message(id, "message/x-whatsapp-message", Set.of("Mensagens"), null, null, chatId,
                1_709_413_000_000L + minute * 60_000L, (int) minute, sender, body);
    }

    public void prepareAll(TextPreparation p, List<TextItem> list) throws InterruptedException {
        for (TextItem t : list) {
            p.prepare(t);
        }
    }

    /** Opens a visual processing of this case with an embedder and the keys in {@code s}. */
    public VisualPreparation openVisual(Embedder e, VisualPreparation.Settings s) throws Exception {
        SourceValidator.Validated v;
        try {
            v = SourceValidator.validate(config, Modality.VISUAL, SemanticSearchConfig.ENABLE_IMAGES, provider(e));
        } catch (SemanticConfigException ex) {
            throw new IllegalStateException(ex);
        }
        return VisualPreparation.open(moduleDir, config, v, s.ipedVersion("test"));
    }

    /** A solid-colour picture, PNG-encoded as a thumbnail would be stored. */
    public static byte[] png(Color color, int w, int h) {
        BufferedImage img = picture(color, w, h);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    public static BufferedImage picture(Color color, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    /** An image item of original size {@code w x h} whose thumbnail is a solid colour. */
    public VisualItem image(int id, Color color, int w, int h) {
        items.set(id);
        byte[] thumb = png(color, Math.min(w, 160), Math.min(h, 160));
        return VisualItem.image(id, Set.of("Imagens"), w, h, () -> thumb);
    }

    /** A video whose mosaic has one solid-colour frame per colour; no colours means no mosaic. */
    public VisualItem video(int id, long durationMs, Color... frames) {
        items.set(id);
        List<BufferedImage> list = new java.util.ArrayList<>();
        for (Color c : frames) {
            list.add(picture(c, 64, 48));
        }
        return VisualItem.video(id, Set.of("Vídeos"), () -> list.isEmpty() ? null : list, durationMs);
    }

    public VisualItem frameSubitem(int id, Color color) {
        items.set(id);
        byte[] thumb = png(color, 64, 48);
        return VisualItem.videoFrameSubitem(id, Set.of("Imagens"), 64, 48, () -> thumb);
    }

    public VisualItem other(int id) {
        items.set(id);
        return VisualItem.other(id, Set.of("Documentos"));
    }

    public SemanticSearcher searcher(Embedder e) {
        return new SemanticSearcher(moduleDir, items, config, provider(e));
    }

    public Path semanticDir() {
        return moduleDir.toPath().resolve("semantic");
    }
}
