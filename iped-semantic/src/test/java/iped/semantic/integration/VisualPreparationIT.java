package iped.semantic.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.TestSupport;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.model.VectorMath;
import iped.semantic.prep.FrameInstant;
import iped.semantic.search.Coverage;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticRequest.Target;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.CoverageReason;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;
import iped.semantic.task.VisualItem;
import iped.semantic.task.VisualPreparation;

/**
 * The visual preparation (story 6): each key governs only what it names, every item gets a reason,
 * conflicts and the time of images and videos are recorded apart (FR-021, SC-015), and the search
 * shows a video once, by its best frame, in an ordering of its own (FR-034).
 */
public class VisualPreparationIT {

    private static final FakeEmbedder E = new FakeEmbedder();

    private static Map<Integer, CoverageReason> reasons(SyntheticCase c, int... ids) throws Exception {
        Map<Integer, CoverageReason> out = new java.util.TreeMap<>();
        try (SemanticSearcher s = c.searcher(E)) {
            for (int id : ids) {
                out.put(id, s.reasonOf(Modality.VISUAL, id));
            }
        }
        return out;
    }

    @Test
    public void imagesOnAndVideosOffGovernOnlyWhatTheyName() throws Exception {
        SyntheticCase c = new SyntheticCase("visual-keys");
        VisualPreparation p = c.openVisual(E, new VisualPreparation.Settings().images(true).videos(false));
        List<VisualItem> items = new ArrayList<>();
        items.add(c.image(1, Color.RED, 640, 480));
        items.add(c.image(2, Color.BLUE, 20, 30)); // below minImageSize = 48
        items.add(c.video(3, 60_000, Color.GREEN, Color.YELLOW));
        items.add(c.frameSubitem(4, Color.CYAN)); // videos off: a frame subitem is just an image
        items.add(c.other(5));
        c.items.set(6);
        items.add(VisualItem.image(6, Set.of("Imagens"), 800, 600, () -> null)); // no thumbnail
        c.items.set(7);
        items.add(VisualItem.image(7, Set.of("Imagens"), 800, 600, () -> new byte[] { 1, 2, 3, 4 }));
        for (VisualItem i : items) {
            p.prepare(i);
        }
        p.finish();

        Map<Integer, CoverageReason> r = reasons(c, 1, 2, 3, 4, 5, 6, 7);
        assertEquals(CoverageReason.COVERED, r.get(1));
        assertEquals(CoverageReason.IMAGE_TOO_SMALL, r.get(2));
        assertEquals("videos off: the video is not read, and says why", CoverageReason.MODALITY_OFF, r.get(3));
        assertEquals(CoverageReason.COVERED, r.get(4));
        assertEquals(CoverageReason.CATEGORY_NOT_SELECTED, r.get(5));
        assertEquals(CoverageReason.NO_THUMBNAIL, r.get(6));
        assertEquals(CoverageReason.IMAGE_UNDECODABLE, r.get(7));

        BuildRecord.ModalityRecord mr = BuildRecord.read(c.semanticDir().resolve("build-record.json"))
                .get(Modality.VISUAL);
        assertEquals(BuildRecord.State.COMPLETE, mr.state);
        assertEquals(Boolean.TRUE, mr.keys.get("images"));
        assertEquals(Boolean.FALSE, mr.keys.get("videos"));
        assertTrue("no conflict: thumbnails are on", mr.conflicts.isEmpty());
        assertEquals("counts add up to the items seen", 7L,
                mr.counts.values().stream().mapToLong(Long::longValue).sum());
        assertTrue("timing of images apart (SC-015)", mr.timing.get("images") instanceof Map);
        assertTrue("timing of videos apart (SC-015)", mr.timing.get("videos") instanceof Map);
        assertEquals(2, ((Number) ((Map<?, ?>) mr.timing.get("images")).get("pictures")).intValue());
        assertEquals(0, ((Number) ((Map<?, ?>) mr.timing.get("videos")).get("pictures")).intValue());

        try (SemanticSearcher s = c.searcher(E)) {
            Coverage cov = s.coverage(Modality.VISUAL, null);
            assertEquals("covered + not covered = the case", cov.getRecorteSize(),
                    cov.getCovered() + cov.getNotCoveredTotal());
            assertEquals(2, cov.getCovered());
        }
    }

    @Test
    public void videosOnWithoutVideoThumbnailsIsDeclaredAndFrameSubitemsDefer() throws Exception {
        SyntheticCase c = new SyntheticCase("visual-conflict");
        VisualPreparation p = c.openVisual(E,
                new VisualPreparation.Settings().images(true).videos(true).videoThumbs(false));
        p.prepare(c.video(1, 60_000)); // no mosaic
        p.prepare(c.frameSubitem(2, Color.CYAN));
        p.prepare(c.image(3, Color.RED, 640, 480));
        p.finish();

        Map<Integer, CoverageReason> r = reasons(c, 1, 2, 3);
        assertEquals(CoverageReason.VIDEO_NO_FRAMES, r.get(1));
        assertEquals("videos on: the frame is covered by its video", CoverageReason.COVERED_BY_SOURCE_VIDEO, r.get(2));
        assertEquals(CoverageReason.COVERED, r.get(3));
        BuildRecord.ModalityRecord mr = BuildRecord.read(c.semanticDir().resolve("build-record.json"))
                .get(Modality.VISUAL);
        assertTrue(mr.conflicts.contains(VisualPreparation.CONFLICT_VIDEOS_WITHOUT_THUMBS));
    }

    @Test
    public void aVideoAppearsOnceByItsBestFrameWithAnApproximateInstant() throws Exception {
        SyntheticCase c = new SyntheticCase("visual-search");
        VisualPreparation p = c.openVisual(E, new VisualPreparation.Settings().images(true).videos(true));
        Color[] frames = { Color.RED, Color.GREEN, Color.BLUE, Color.ORANGE };
        p.prepare(c.video(10, 600_000, frames));
        p.prepare(c.video(11, 120_000, Color.YELLOW, Color.PINK));
        p.prepare(c.image(12, Color.MAGENTA, 640, 480));
        p.finish();

        String question = "uma cena qualquer";
        float[] q = E.embed(question);
        int best = -1;
        double bestScore = -2;
        for (int k = 0; k < frames.length; k++) {
            double s = VectorMath.dot(q, E.embedImages(List.of(SyntheticCase.picture(frames[k], 64, 48)))[0]);
            if (s > bestScore) {
                bestScore = s;
                best = k;
            }
        }

        try (SemanticSearcher s = c.searcher(E)) {
            Ordering o = s.search(SemanticRequest.builder().target(Target.VISUAL).query(question)
                    .mode(TextMode.HYBRID).build(c.config)).get(0);
            assertEquals(Modality.VISUAL, o.getModality());
            assertEquals("the visual modality is always semantic", TextMode.SEMANTIC, o.getMode());
            assertEquals(3, o.getAll().size());
            int videos = 0;
            for (OrderingEntry e : o.getAll()) {
                assertEquals(OrderingEntry.ProximityKind.RELATIVE, e.getProximityKind());
                assertTrue("no text in a visual entry", e.getExcerpts().isEmpty());
                if (e.getItemId() == 10) {
                    videos++;
                    assertEquals("video", e.getKind());
                    assertNotNull(e.getFrame());
                    assertEquals("the best frame represents the video", best, e.getFrame().getFrameIndex());
                    assertEquals(Integer.valueOf(frames.length), e.getFrame().getFrameCount());
                    assertEquals(FrameInstant.approxMillis(600_000L, best, frames.length),
                            e.getFrame().getInstantApproxMs());
                    assertEquals(bestScore, e.getProximity(), 1e-5);
                } else if (e.getItemId() == 12) {
                    assertEquals("image", e.getKind());
                }
            }
            assertEquals("one entry per video", 1, videos);
        }
    }

    @Test
    public void bothModalitiesGiveTwoOrderingsNeverMerged() throws Exception {
        TestSupport.ensureConfiguration();
        SyntheticCase c = new SyntheticCase("visual-both");
        TextPreparation t = c.open(E, null);
        List<TextItem> texts = new ArrayList<>();
        texts.add(c.doc(1, "uma praia ao pôr do sol com barcos"));
        texts.add(c.doc(2, "relatório financeiro trimestral"));
        c.prepareAll(t, texts);
        t.finish();
        VisualPreparation v = c.openVisual(E, new VisualPreparation.Settings().images(true).videos(false));
        v.prepare(c.image(3, Color.ORANGE, 640, 480));
        v.prepare(c.image(4, Color.BLUE, 640, 480));
        v.prepare(c.other(1));
        v.prepare(c.other(2));
        v.finish();

        try (SemanticSearcher s = c.searcher(E)) {
            List<Ordering> both = s.search(SemanticRequest.builder().target(Target.BOTH).query("praia")
                    .build(c.config));
            assertEquals(2, both.size());
            assertEquals(Modality.TEXT, both.get(0).getModality());
            assertEquals(Modality.VISUAL, both.get(1).getModality());
            for (OrderingEntry e : both.get(0).getAll()) {
                assertFalse("no picture in the text ordering", e.getItemId() == 3 || e.getItemId() == 4);
            }
            for (OrderingEntry e : both.get(1).getAll()) {
                assertEquals("only pictures in the visual ordering", "image", e.getKind());
            }
        }
    }
}
