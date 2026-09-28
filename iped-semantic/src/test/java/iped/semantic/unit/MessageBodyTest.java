package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.apache.tika.metadata.Metadata;
import org.junit.Test;

import iped.properties.ExtraProperties;
import iped.semantic.task.SemanticTextTaskAccess;

public class MessageBodyTest {

    private static String body(String... values) {
        Metadata md = new Metadata();
        for (String v : values) {
            md.add(ExtraProperties.MESSAGE_BODY, v);
        }
        return SemanticTextTaskAccess.messageBody(md);
    }

    @Test
    public void notAMessageWithoutBody() {
        assertNull(SemanticTextTaskAccess.messageBody(new Metadata()));
    }

    @Test
    public void parserTypeMarkersAreNotText() {
        assertEquals("", body("! USER_ADDED_TO_GROUP"));
        assertEquals("", body("! MESSAGES_NOW_ENCRYPTED"));
    }

    @Test
    public void aCaptionAddedAfterTheMarkerIsTheText() {
        assertEquals("olha o pacote na portaria", body("! IMAGE_MESSAGE", "olha o pacote na portaria"));
    }

    @Test
    public void telegramAttachmentMarkersAreNotText() {
        assertEquals("", body("! Attachment: image/jpeg"));
        assertEquals("", body("! Attachment: application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        // Telegram sets the caption first and adds the marker after it
        assertEquals("olha o pacote na portaria", body("olha o pacote na portaria", "! Attachment: video/mp4"));
        assertEquals("! Attachment: segue o contrato", body("! Attachment: segue o contrato"));
    }

    @Test
    public void ordinaryTextIsKeptLiterally() {
        assertEquals("fechado, amanhã na portaria", body("fechado, amanhã na portaria"));
        assertEquals("! atenção, é urgente", body("! atenção, é urgente"));
    }
}
