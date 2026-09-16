package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfDocumentExtractorTest {

    @Test
    void usesPdfTextLayerWhenItContainsText() throws Exception {
        AtomicInteger ocrCalls = new AtomicInteger();
        PdfDocumentExtractor extractor = new PdfDocumentExtractor(image -> {
            ocrCalls.incrementAndGet();
            return "OCR fallback";
        }, 3, 100, 72);

        DocumentContent result = extractor.extract(pdfWithText("Hello PDF"));

        assertThat(result.blocks()).extracting(DocumentBlock::text).containsExactly("Hello PDF");
        assertThat(ocrCalls).hasValue(0);
    }

    @Test
    void fallsBackToOcrForScannedPage() throws Exception {
        AtomicInteger ocrCalls = new AtomicInteger();
        PdfDocumentExtractor extractor = new PdfDocumentExtractor(image -> {
            ocrCalls.incrementAndGet();
            return "扫描识别结果";
        }, 3, 100, 72);

        DocumentContent result = extractor.extract(blankPdf());

        assertThat(result.blocks()).extracting(DocumentBlock::text).containsExactly("扫描识别结果");
        assertThat(ocrCalls).hasValue(1);
    }

    @Test
    void keepsOtherPagesWhenOcrFailsOnAnUnpositionedPage() throws Exception {
        byte[] mixed;
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(
                pdfWithText("The study evaluates document translation quality."));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            mixed = output.toByteArray();
        }
        PdfDocumentExtractor extractor = new PdfDocumentExtractor(image -> {
            throw new InvalidDocumentException("OCR engine failed");
        }, 20, 100, 72);

        DocumentContent result = extractor.extract(mixed);

        assertThat(result.blocks()).extracting(DocumentBlock::text)
                .containsExactly("The study evaluates document translation quality.");
        assertThat(result.ocrPages()).containsExactly(2);
    }

    @Test
    void joinsWrappedProseButKeepsEquationOutsideTranslationBlocks() throws Exception {
        AtomicInteger ocrCalls = new AtomicInteger();
        PdfDocumentExtractor extractor = new PdfDocumentExtractor(image -> {
            ocrCalls.incrementAndGet();
            return "unexpected OCR";
        }, 20, 100, 72);

        DocumentContent result = extractor.extract(pdfWithLines(
                "Large language models can produce",
                "coherent answers for translation.",
                "CTT = x + y",
                "The study evaluates document level",
                "translation quality on two datasets."));

        assertThat(result.blocks()).extracting(DocumentBlock::text).containsExactly(
                "Large language models can produce coherent answers for translation.",
                "CTT = x + y",
                "The study evaluates document level translation quality on two datasets.");
        assertThat(ocrCalls).hasValue(0);
    }

    @Test
    void doesNotHideProseThatFollowsAnEquation() throws Exception {
        List<DocumentBlock> result = PdfTextBlockAssembler.assemble(String.join("\n",
                "CTT = x + y",
                "for each terminology word w ∈ TT, the score is computed.",
                "The higher the score, the more consistent the translation."));

        assertThat(result).extracting(DocumentBlock::text).containsExactly(
                "CTT = x + y",
                "for each terminology word w ∈ TT, the score is computed.",
                "The higher the score, the more consistent the translation.");
    }

    @Test
    void keepsContinuousProseTogetherBeyondSixHundredCharacters() {
        String sentence = "This paper studies coherent document translation across multiple paragraphs. ";
        String text = sentence.repeat(12);

        List<DocumentBlock> blocks = PdfTextBlockAssembler.assemble(text);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo(text.strip());
    }

    @Test
    void protectsOnlyTheLineContainingAnInlineVariable() {
        List<DocumentBlock> blocks = PdfTextBlockAssembler.assemble(String.join("\n",
                "The paper introduces a useful score.",
                "For each word w ∈ TT, the score is computed.",
                "The next paragraph explains translation quality."));

        assertThat(blocks).extracting(DocumentBlock::type).containsExactly(
                BlockType.PARAGRAPH, BlockType.PROTECTED_LINE, BlockType.PARAGRAPH);
    }

    @Test
    void separatesProseWhenTextLayerSwitchesColumns() {
        String text = "The left column describes a method.\nIt improves translation quality.\n"
                + "The right column evaluates a model.\nIt compares the results.";
        List<PdfVisualLine> lines = List.of(
                new PdfVisualLine("The left column describes a method.", 72, 100, 300, 10),
                new PdfVisualLine("It improves translation quality.", 72, 112, 300, 10),
                new PdfVisualLine("The right column evaluates a model.", 320, 100, 550, 10),
                new PdfVisualLine("It compares the results.", 320, 112, 550, 10));

        assertThat(PdfTextBlockAssembler.assembleVisual(text, lines, 612))
                .extracting(DocumentBlock::text).containsExactly(
                        "The left column describes a method. It improves translation quality.",
                        "The right column evaluates a model. It compares the results.");
    }

    @Test
    void separatesIndentedVisualParagraphsInOneColumn() {
        String text = "The method considers previous context.\n"
                + "The experiment evaluates translation quality.";
        List<PdfVisualLine> lines = List.of(
                new PdfVisualLine("The method considers previous context.", 72, 100, 300, 10),
                new PdfVisualLine("The experiment evaluates translation quality.", 82, 112, 300, 10));

        assertThat(PdfTextBlockAssembler.assembleVisual(text, lines, 612))
                .extracting(DocumentBlock::text).containsExactly(
                        "The method considers previous context.",
                        "The experiment evaluates translation quality.");
    }

    @Test
    void separatesNearbyColumnsEvenWhenTheLeftLineHasNoSentenceEnd() {
        String text = "Left column text continues\nRight column text continues";
        List<PdfVisualLine> lines = List.of(
                new PdfVisualLine("Left column text continues", 72, 100, 180, 10),
                new PdfVisualLine("Right column text continues", 190, 100, 300, 10));

        assertThat(PdfTextBlockAssembler.assembleVisual(text, lines, 612))
                .extracting(DocumentBlock::text).containsExactly(
                        "Left column text continues", "Right column text continues");
    }

    @Test
    void keepsUnalignedVisualTextOutOfTranslationBlocks() {
        assertThat(PdfTextBlockAssembler.assembleVisual("Unreliable text layer",
                List.of(new PdfVisualLine("different text", 72, 100, 200, 10)), 612))
                .isEmpty();
    }

    private byte[] pdfWithText(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] blankPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] pdfWithLines(String... lines) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                for (String line : lines) {
                    stream.showText(line);
                    stream.newLineAtOffset(0, -18);
                }
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
