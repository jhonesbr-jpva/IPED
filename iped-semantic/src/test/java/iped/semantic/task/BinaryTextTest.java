package iped.semantic.task;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.junit.Test;

/**
 * Which text counts as drawn from a binary (research D6, amended 2026-09-27): the parser chain IPED
 * records for the item holds its strings extractor, or the item is unallocated space or file slack.
 */
public class BinaryTextTest {

    private static Metadata parsedBy(String... parsers) {
        Metadata md = new Metadata();
        for (String p : parsers) {
            md.add(TikaCoreProperties.TIKA_PARSED_BY, p);
        }
        return md;
    }

    @Test
    public void theStringsExtractorMarksTheTextAsBinary() {
        // executables and libraries: ParserConfig.xml's MultipleParser with the metadata-only ExecutableParser
        assertTrue(SemanticTextTask.isBinaryText("application/x-sharedlib", parsedBy("iped.parsers.misc.MultipleParser",
                "iped.parsers.standard.RawStringParser", "org.apache.tika.parser.executable.ExecutableParser")));
        // the fallback for unknown binaries
        assertTrue(SemanticTextTask.isBinaryText("application/octet-stream",
                parsedBy("iped.parsers.standard.RawStringParser")));
    }

    @Test
    public void unallocatedSpaceAndSlackAreBinaryWhateverParsedThem() {
        assertTrue(SemanticTextTask.isBinaryText("application/x-unallocated", new Metadata()));
        assertTrue(SemanticTextTask.isBinaryText("application/x-fileslack", null));
    }

    @Test
    public void binaryContentReadAsTextIsMostlyUnreadable() {
        StringBuilder blob = new StringBuilder("SQLite format 3");
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            int b = r.nextInt(256);
            blob.append(b < 32 || (b >= 0x7f && b < 0xa0) ? (char) b : b > 0xf0 ? '�' : (char) b);
        }
        assertTrue(TextPreparation.unreadableShare(blob.toString()) >= TextPreparation.UNREADABLE_SHARE);
    }

    @Test
    public void writtenTextEmojiAndAccentsAreReadable() {
        assertTrue(TextPreparation.unreadableShare("Deixa o pacote com o moleque da portaria, o acerto é amanhã.") == 0);
        assertTrue(TextPreparation.unreadableShare("MOVIMENTO PRA FRENTE 🚀🇧🇷 ok\tfim\r\n") == 0);
        assertTrue(TextPreparation.unreadableShare("Cristo Redentor é uma estátua; 中文文本") == 0);
        assertTrue(TextPreparation.unreadableShare("") == 0);
    }

    @Test
    public void textParsersOcrAndDocumentsAreNot() {
        assertFalse(SemanticTextTask.isBinaryText("text/plain", parsedBy("org.apache.tika.parser.csv.TextAndCSVParser")));
        assertFalse(SemanticTextTask.isBinaryText("image/png",
                parsedBy("iped.parsers.misc.MultipleParser", "iped.parsers.ocr.OCRParser")));
        assertFalse(SemanticTextTask.isBinaryText("application/pdf", parsedBy("iped.parsers.pdf.PDFTextParser")));
        assertFalse(SemanticTextTask.isBinaryText("text/html", null));
    }
}
