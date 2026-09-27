package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import iped.semantic.prep.ChatSequenceBuilder;
import iped.semantic.prep.ChatSequenceBuilder.Window;
import iped.semantic.store.SequenceLine;
import iped.semantic.store.StagingStore.Message;

public class ChatSequenceBuilderTest {

    private static final long T0 = 1_709_413_000_000L; // 2024-03-02
    private static final long MIN = 60_000L;

    private static ChatSequenceBuilder builder() {
        return new ChatSequenceBuilder(8, 4, 1500, 720, 20);
    }

    private static Message msg(int id, long minute, Integer pos, String sender, String text) {
        return new Message(1, id, T0 + minute * MIN, pos, sender, text);
    }

    private static List<Integer> ids(Window w) {
        return w.lines().stream().map(SequenceLine::getItemId).collect(Collectors.toList());
    }

    @Test
    public void ordersByDateThenPositionThenId() {
        List<Message> in = new ArrayList<>();
        in.add(msg(30, 5, 2, "A", "terceira mensagem da conversa sobre o assunto"));
        in.add(msg(10, 1, 1, "B", "primeira mensagem da conversa sobre o assunto"));
        in.add(msg(21, 5, 1, "A", "segunda mensagem, mesma hora, posição menor"));
        in.add(msg(20, 5, 1, "A", "segunda mensagem bis, mesma hora e posição, id menor"));
        Collections.shuffle(in, new java.util.Random(7));
        List<Window> ws = builder().build(in);
        assertEquals(1, ws.size());
        assertEquals(List.of(10, 20, 21, 30), ids(ws.get(0)));
    }

    @Test
    public void windowsOfEightEveryFour() {
        List<Message> in = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            in.add(msg(100 + i, i, i, i % 2 == 0 ? "Ana" : "Beto", "mensagem de conteúdo número " + i));
        }
        List<Window> ws = builder().build(in);
        assertEquals(List.of(100, 101, 102, 103, 104, 105, 106, 107), ids(ws.get(0)));
        assertEquals(List.of(104, 105, 106, 107, 108, 109, 110, 111), ids(ws.get(1)));
        Set<Integer> covered = new HashSet<>();
        for (int k = 0; k < ws.size(); k++) {
            assertEquals(k, ws.get(k).ordinal());
            covered.addAll(ids(ws.get(k)));
            assertTrue(ws.get(k).lines().size() <= 8);
        }
        assertEquals("no message skipped", 20, covered.size());
        assertEquals(119, (int) ids(ws.get(ws.size() - 1)).get(ids(ws.get(ws.size() - 1)).size() - 1));
    }

    @Test
    public void characterLimitShortensWindowsWithoutSkippingMessages() {
        List<Message> in = new ArrayList<>();
        String para = "x".repeat(600);
        for (int i = 0; i < 6; i++) {
            in.add(msg(200 + i, i, i, "A", para + i));
        }
        List<Window> ws = builder().build(in);
        Set<Integer> covered = new HashSet<>();
        for (Window w : ws) {
            int chars = w.lines().stream().mapToInt(l -> l.getText().length()).sum();
            assertTrue(chars <= 1500 || w.lines().size() == 1);
            covered.addAll(ids(w));
        }
        assertEquals(6, covered.size());
    }

    @Test
    public void aLongGapBreaksTheConversation() {
        List<Message> in = new ArrayList<>();
        in.add(msg(1, 0, 0, "A", "vamos combinar o preço da mercadoria"));
        in.add(msg(2, 1, 1, "B", "pode ser amanhã na portaria então"));
        in.add(msg(3, 60 * 24 * 3, 2, "A", "três dias depois, outro assunto totalmente diferente"));
        in.add(msg(4, 60 * 24 * 3 + 1, 3, "B", "sim, sobre a viagem de férias da família"));
        List<Window> ws = builder().build(in);
        assertEquals(2, ws.size());
        assertEquals(List.of(1, 2), ids(ws.get(0)));
        assertEquals(List.of(3, 4), ids(ws.get(1)));
    }

    @Test
    public void aShortMessageNeverFormsAUnitAlone() {
        List<Message> in = new ArrayList<>();
        in.add(msg(1, 0, 0, "A", "quanto fica a entrega completa da carga?"));
        in.add(msg(2, 1, 1, "B", "cinco mil, fechado para amanhã cedo"));
        in.add(msg(3, 60 * 24 * 2, 2, "A", "ok")); // isolated by a two-day gap
        List<Window> ws = builder().build(in);
        for (Window w : ws) {
            assertFalse("short message alone: " + ids(w), w.lines().size() == 1 && w.lines().get(0).getText().length() < 20);
        }
        assertTrue(ws.stream().anyMatch(w -> ids(w).contains(3)));
    }

    @Test
    public void aChatWhoseOnlyContentIsAShortMessageIsKept() {
        List<Window> ws = builder().build(List.of(msg(9, 0, 0, "A", "blz")));
        assertEquals(1, ws.size());
    }

    @Test
    public void linesAreLiteralAndTheEmbeddingTextCarriesTheSender() {
        List<Message> in = List.of(msg(1, 0, 0, "Fulano", "quanto?"), msg(2, 1, 1, "Beltrano", "5 mil"),
                msg(3, 2, 2, "Fulano", "fechado, amanhã na portaria"));
        Window w = builder().build(in).get(0);
        assertEquals(List.of(1, 2, 3), ids(w));
        assertEquals("quanto?", w.lines().get(0).getText());
        assertEquals("5 mil", w.lines().get(1).getText());
        assertEquals("fechado, amanhã na portaria", w.lines().get(2).getText());
        assertEquals("Fulano: quanto?\nBeltrano: 5 mil\nFulano: fechado, amanhã na portaria", w.embeddingText());
        assertEquals(Long.valueOf(T0), w.lines().get(0).getDateMillis());
    }

    @Test
    public void messagesWithoutTextStayOut() {
        List<Message> in = List.of(msg(1, 0, 0, "A", "olha a foto do pacote aqui"), msg(2, 1, 1, "A", ""),
                msg(3, 2, 2, "B", "recebi, confere com o combinado"));
        assertEquals(List.of(1, 3), ids(builder().build(in).get(0)));
    }
}
