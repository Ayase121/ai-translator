package com.example.aitranslator.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocxDocumentCodecTest {

    @Test
    void writesAndReadsParagraphsAndTableCells() throws Exception {
        DocumentContent original = new DocumentContent(List.of(
                new DocumentBlock(BlockType.HEADING, "Translated title", 0, 0),
                new DocumentBlock(BlockType.PARAGRAPH, "Translated paragraph", 0, 0),
                new DocumentBlock(BlockType.TABLE_CELL, "Cell A", 0, 0),
                new DocumentBlock(BlockType.TABLE_CELL, "Cell B", 0, 1)
        ));

        byte[] bytes = new DocxDocumentWriter().write(original, "translated.docx");
        DocumentContent decoded = new DocxDocumentExtractor().extract(bytes);

        assertThat(decoded.blocks()).extracting(DocumentBlock::text)
                .containsExactly("Translated title", "Translated paragraph", "Cell A", "Cell B");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(document.getTables()).hasSize(1);
            assertThat(document.getTables().get(0).getRow(0).getTableCells()).hasSize(2);
        }
    }

    @Test
    void preservesTwoTablesInTheirBodyOrder() throws Exception {
        DocumentContent original = new DocumentContent(List.of(
                new DocumentBlock(BlockType.PARAGRAPH, "Before", 0, 0, -1),
                new DocumentBlock(BlockType.TABLE_CELL, "First table", 0, 0, 0),
                new DocumentBlock(BlockType.PARAGRAPH, "Between", 0, 0, -1),
                new DocumentBlock(BlockType.TABLE_CELL, "Second table", 0, 0, 1),
                new DocumentBlock(BlockType.PARAGRAPH, "After", 0, 0, -1)
        ));

        byte[] bytes = new DocxDocumentWriter().write(original, "ordered.docx");
        DocumentContent decoded = new DocxDocumentExtractor().extract(bytes);

        assertThat(decoded.blocks()).extracting(DocumentBlock::text)
                .containsExactly("Before", "First table", "Between", "Second table", "After");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(document.getTables()).hasSize(2);
        }
    }
}
