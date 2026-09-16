package com.example.aitranslator.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PdfFixedLayoutDocxWriterTest {

    @Test
    void writesOriginalPageImageAndOnlyChangesSafeProseArea() throws Exception {
        byte[] pdf = pdfPageWithProseAndEquation();
        DocumentContent original = new PdfDocumentExtractor(image -> "", 20, 100, 72).extract(pdf);
        List<DocumentBlock> replacements = new ArrayList<>();
        for (DocumentBlock block : original.blocks()) {
            replacements.add(new DocumentBlock(block.type(),
                    block.type() == BlockType.FORMULA ? block.text() : "本文讨论文档翻译。",
                    block.rowIndex(), block.columnIndex(), block.tableIndex(), block.pageNumber()));
        }

        PdfFixedLayoutDocxWriter.WriteResult result = new PdfFixedLayoutDocxWriter()
                .write(pdf, original, new DocumentContent(replacements), Set.of(), "paper-translated.docx");

        assertThat(result.placedBlocks()).isGreaterThan(0);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(result.bytes()));
             PDDocument source = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertThat(document.getAllPictures()).hasSize(1);
            BufferedImage output = ImageIO.read(new ByteArrayInputStream(document.getAllPictures().get(0).getData()));
            BufferedImage input = new PDFRenderer(source).renderImageWithDPI(0, 144, ImageType.RGB);
            assertThat(output.getWidth()).isEqualTo(input.getWidth());
            assertThat(output.getHeight()).isEqualTo(input.getHeight());
            // The untouched black figure rectangle is still in the original position.
            assertThat(anyPixelChanged(input, output, 780, 140, 910, 274)).isFalse();
            assertThat(anyPixelChanged(input, output, 140, 270, 350, 320)).isFalse();
            assertThat(anyPixelChanged(input, output, 140, 160, 560, 230))
                    .as("changed bounds: %s", changedBounds(input, output)).isTrue();
            // The original first-line glyphs must be removed all the way up to their top edge.
            assertThat(output.getRGB(150, 167)).isNotEqualTo(input.getRGB(150, 167));
        }
    }

    @Test
    void keepsWholePageWhenBlockMappingIsUnreliable() throws Exception {
        byte[] pdf = pdfPageWithProseAndEquation();
        DocumentContent original = new PdfDocumentExtractor(image -> "", 20, 100, 72).extract(pdf);
        DocumentContent mismatched = new DocumentContent(List.of(
                new DocumentBlock(BlockType.PARAGRAPH, "译文", 0, 0, -1, 1)));

        PdfFixedLayoutDocxWriter.WriteResult result = new PdfFixedLayoutDocxWriter()
                .write(pdf, original, mismatched, Set.of(), "paper-translated.docx");

        assertThat(result.placedBlocks()).isZero();
        assertThat(result.retainedBlocks()).isGreaterThan(0);
    }

    @Test
    void leavesTextInsideARuledGraphicUnchanged() throws Exception {
        byte[] pdf = pdfPageWithProseAndEquation(true);
        DocumentContent original = new PdfDocumentExtractor(image -> "", 20, 100, 72).extract(pdf);
        List<DocumentBlock> replacements = original.blocks().stream().map(block -> new DocumentBlock(
                block.type(), block.type() == BlockType.FORMULA ? block.text() : "这是译文。",
                block.rowIndex(), block.columnIndex(), block.tableIndex(), block.pageNumber())).toList();

        PdfFixedLayoutDocxWriter.WriteResult result = new PdfFixedLayoutDocxWriter().write(pdf, original,
                new DocumentContent(replacements), Set.of(), "ruled-table.docx");

        assertThat(result.placedBlocks()).isZero();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(result.bytes()));
             PDDocument source = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            BufferedImage output = ImageIO.read(new ByteArrayInputStream(document.getAllPictures().get(0).getData()));
            BufferedImage input = new PDFRenderer(source).renderImageWithDPI(0, 144, ImageType.RGB);
            assertThat(changedBounds(input, output)).isEqualTo(input.getWidth() + "," + input.getHeight() + "..0,0");
        }
    }

    @Test
    void acceptsAdjacentWrappedLinesWithoutBoundingBoxCollision() throws Exception {
        byte[] pdf = pdfPageWithProseAndEquation();
        DocumentContent original = new PdfDocumentExtractor(image -> "", 20, 100, 72).extract(pdf);
        List<DocumentBlock> replacements = original.blocks().stream().map(block -> new DocumentBlock(
                block.type(), block.type() == BlockType.FORMULA ? block.text() : "这是相邻正文行的译文。",
                block.rowIndex(), block.columnIndex(), block.tableIndex(), block.pageNumber())).toList();

        PdfFixedLayoutDocxWriter.WriteResult result = new PdfFixedLayoutDocxWriter().write(pdf, original,
                new DocumentContent(replacements), Set.of(), "adjacent.docx");

        assertThat(result.placedBlocks()).isGreaterThan(0);
        assertThat(result.coveredLines()).isEqualTo(result.eligibleLines());
    }

    @Test
    void preservesVerticalTableRuleAcrossProse() throws Exception {
        assertGraphicUntouched(pdfPageWithGraphic("vertical"));
    }

    @Test
    void preservesColoredFigureBehindProse() throws Exception {
        assertGraphicUntouched(pdfPageWithGraphic("color"));
    }

    @Test
    void preservesPaleDashedTableLineAcrossProse() throws Exception {
        assertGraphicUntouched(pdfPageWithGraphic("gray-dashed"));
    }

    @Test
    void preservesSmallBlackAndWhiteArtworkAcrossProse() throws Exception {
        assertGraphicUntouched(pdfPageWithGraphic("small-black"));
    }


    private void assertGraphicUntouched(byte[] pdf) throws Exception {
        DocumentContent original = new PdfDocumentExtractor(image -> "", 20, 100, 72).extract(pdf);
        List<DocumentBlock> replacements = original.blocks().stream().map(block -> new DocumentBlock(
                block.type(), block.type() == BlockType.FORMULA ? block.text() : "这是译文。",
                block.rowIndex(), block.columnIndex(), block.tableIndex(), block.pageNumber())).toList();
        PdfFixedLayoutDocxWriter.WriteResult result = new PdfFixedLayoutDocxWriter().write(pdf, original,
                new DocumentContent(replacements), Set.of(), "graphic.docx");

        assertThat(result.placedBlocks()).isZero();
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(result.bytes()));
             PDDocument source = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            BufferedImage output = ImageIO.read(new ByteArrayInputStream(docx.getAllPictures().get(0).getData()));
            BufferedImage input = new PDFRenderer(source).renderImageWithDPI(0, 144, ImageType.RGB);
            assertThat(changedBounds(input, output)).isEqualTo(input.getWidth() + "," + input.getHeight() + "..0,0");
        }
    }

    private boolean anyPixelChanged(BufferedImage original, BufferedImage translated,
                                    int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (original.getRGB(x, y) != translated.getRGB(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String changedBounds(BufferedImage original, BufferedImage translated) {
        int left = original.getWidth(), top = original.getHeight(), right = 0, bottom = 0;
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                if (original.getRGB(x, y) != translated.getRGB(x, y)) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return left + "," + top + ".." + right + "," + bottom;
    }

    private byte[] pdfPageWithProseAndEquation() throws Exception {
        return pdfPageWithProseAndEquation(false);
    }

    private byte[] pdfPageWithProseAndEquation(boolean ruledGraphic) throws Exception {
        return pdfPageWithGraphic(ruledGraphic ? "horizontal" : "none");
    }

    private byte[] pdfPageWithGraphic(String graphic) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setNonStrokingColor(0, 0, 0);
                stream.addRect(390, 655, 65, 65);
                stream.fill();
                if (graphic.equals("horizontal")) {
                    stream.moveTo(70, 692);
                    stream.lineTo(300, 692);
                    stream.stroke();
                } else if (graphic.equals("vertical")) {
                    stream.moveTo(100, 675);
                    stream.lineTo(100, 725);
                    stream.stroke();
                } else if (graphic.equals("gray-dashed")) {
                    stream.setStrokingColor(.82f, .82f, .82f);
                    stream.setLineDashPattern(new float[] {3f, 3f}, 0f);
                    stream.moveTo(70, 692);
                    stream.lineTo(300, 692);
                    stream.stroke();
                } else if (graphic.equals("small-black")) {
                    stream.addRect(110, 685, 20, 15);
                    stream.fill();
                } else if (graphic.equals("color")) {
                    stream.setNonStrokingColor(1f, 0f, 0f);
                    stream.addRect(110, 685, 90, 28);
                    stream.fill();
                    stream.setNonStrokingColor(0, 0, 0);
                }
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText("This method improves translation.");
                stream.newLineAtOffset(0, -18);
                stream.showText("It uses context from the document.");
                stream.newLineAtOffset(0, -35);
                stream.showText("CTT = x + y");
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
