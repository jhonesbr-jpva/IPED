package iped.semantic.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.apache.tika.metadata.Metadata;
import org.junit.Test;

/**
 * What the visual task reads from an item's metadata: the original dimensions of an image and the
 * duration of a video, as the engine leaves them when the task runs (after parsing).
 */
public class VisualMetadataTest {

    @Test
    public void theDurationIsReadUnderTheVideoPrefixOrWithout() {
        Metadata md = new Metadata();
        md.set("video:xmpDM:duration", "39.96");
        assertEquals(Long.valueOf(39_960), SemanticVisualTask.durationMs(md));

        Metadata raw = new Metadata();
        raw.set("xmpDM:duration", "600.0");
        assertEquals("as VideoThumbTask writes it, before normalisation", Long.valueOf(600_000),
                SemanticVisualTask.durationMs(raw));

        assertNull(SemanticVisualTask.durationMs(new Metadata()));
        assertNull(SemanticVisualTask.durationMs(null));
        Metadata bad = new Metadata();
        bad.set("video:xmpDM:duration", "unknown");
        assertNull(SemanticVisualTask.durationMs(bad));
    }

    @Test
    public void dimensionsAreTheLeadingInteger() {
        Metadata md = new Metadata();
        md.set("image:Width", "640");
        md.set("image:Height", "480 pixels");
        assertEquals(Integer.valueOf(640), SemanticVisualTask.dimension(md, "image:Width"));
        assertEquals(Integer.valueOf(480), SemanticVisualTask.dimension(md, "image:Height"));
        assertNull(SemanticVisualTask.dimension(md, "image:Depth"));
        md.set("image:Depth", "n/a");
        assertNull(SemanticVisualTask.dimension(md, "image:Depth"));
    }
}
