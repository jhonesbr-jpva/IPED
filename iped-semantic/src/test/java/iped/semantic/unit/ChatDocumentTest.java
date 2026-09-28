package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.ModelIdentity;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.store.CoverageReason;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;

/**
 * Rendered conversations (research D7): a chat whose messages become sequences is represented by
 * them, and its rendered text is never embedded; a chat whose messages have no text is not prepared
 * either; a chat with no message items is prepared as the document it is, at the end.
 */
public class ChatDocumentTest {

    /** Records every passage text it embeds. */
    private static final class Recording implements Embedder {
        final FakeEmbedder inner = new FakeEmbedder();
        final List<String> passages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public float[][] embedTexts(Role role, List<String> texts) throws EmbeddingException {
            if (role == Role.PASSAGE) {
                passages.addAll(texts);
            }
            return inner.embedTexts(role, texts);
        }

        @Override
        public ModelIdentity identity() {
            return inner.identity();
        }
    }

    private static TextItem renderedChat(SyntheticCase c, int id, String text) {
        c.items.set(id);
        return TextItem.document(id, "application/x-whatsapp-chat", Set.of("Chats"),
                () -> new StringReader(text + "\n" + SyntheticCase.HEADER + "\nk: v\n"), null);
    }

    @Test
    public void aChatWithSequencesIsNeverEmbeddedAsADocument() throws Exception {
        Recording e = new Recording();
        SyntheticCase c = new SyntheticCase("chatdoc");
        TextPreparation p = c.open(e, null);
        String rendered = "CONVERSA RENDERIZADA Ana: quanto fica a entrega da carga toda? Beto: cinco mil, fechado.";
        List<TextItem> items = new ArrayList<>();
        items.add(renderedChat(c, 10, rendered));
        items.add(c.message(11, 10, 0, "Ana", "quanto fica a entrega da carga toda?"));
        items.add(c.message(12, 10, 1, "Beto", "cinco mil, fechado para amanhã cedo"));
        // a chat without messages: its rendered text is all there is
        items.add(renderedChat(c, 20, "CONVERSA SEM MENSAGENS sobre o aluguel do galpão no porto"));
        // a chat whose messages have no text (a parser marker only)
        items.add(renderedChat(c, 30, "CONVERSA SÓ COM MÍDIA figurinha enviada"));
        items.add(c.message(31, 30, 0, "Ana", ""));
        // a chat whose only message has no body at all
        items.add(renderedChat(c, 40, "CONVERSA SEM CORPO evento do sistema"));
        items.add(bodyless(c, 41, 40));
        // a body-less message beside one with text: the chat still gets its sequence
        items.add(renderedChat(c, 50, "CONVERSA MISTA combinado"));
        items.add(c.message(51, 50, 0, "Beto", "combinado, levo o contrato assinado amanhã"));
        items.add(bodyless(c, 52, 50));
        c.prepareAll(p, items);
        p.finish();

        assertFalse("the rendered text of a sequenced chat is not embedded",
                e.passages.stream().anyMatch(t -> t.contains("CONVERSA RENDERIZADA")));
        assertTrue(e.passages.stream().anyMatch(t -> t.contains("CONVERSA SEM MENSAGENS")));
        for (String nobody : List.of("CONVERSA SÓ COM MÍDIA", "CONVERSA SEM CORPO", "CONVERSA MISTA")) {
            assertFalse(nobody + " is not embedded", e.passages.stream().anyMatch(t -> t.contains(nobody)));
        }
        try (SemanticSearcher s = c.searcher(e.inner)) {
            assertEquals(CoverageReason.COVERED_BY_MESSAGES, s.table(Modality.TEXT).reasonOf(10));
            assertEquals(CoverageReason.COVERED, s.table(Modality.TEXT).reasonOf(11));
            assertEquals(CoverageReason.COVERED, s.table(Modality.TEXT).reasonOf(20));
            assertEquals(CoverageReason.NO_TEXT, s.table(Modality.TEXT).reasonOf(30));
            assertEquals(CoverageReason.NO_TEXT, s.table(Modality.TEXT).reasonOf(31));
            assertEquals(CoverageReason.NO_TEXT, s.table(Modality.TEXT).reasonOf(40));
            assertEquals(CoverageReason.NO_TEXT, s.table(Modality.TEXT).reasonOf(41));
            assertEquals(CoverageReason.COVERED_BY_MESSAGES, s.table(Modality.TEXT).reasonOf(50));
            assertEquals(CoverageReason.COVERED, s.table(Modality.TEXT).reasonOf(51));
            assertEquals(CoverageReason.NO_TEXT, s.table(Modality.TEXT).reasonOf(52));
        }
    }

    /**
     * A message with no Message-Body, as the task builds it: a document of a message type, its text
     * the printed metadata, its empty content read by the strings fallback.
     */
    private static TextItem bodyless(SyntheticCase c, int id, int chatId) {
        c.items.set(id);
        return TextItem.document(id, "message/x-telegram-message", Set.of("Instant Messages"),
                () -> new StringReader("Communication:From: Ana\nisDecodedData: true"), null).parent(chatId)
                .binaryText();
    }
}
