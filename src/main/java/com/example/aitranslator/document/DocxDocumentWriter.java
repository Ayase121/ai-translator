package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DocxDocumentWriter {

    public byte[] write(DocumentContent content, String title) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Set<Integer> writtenTables = new HashSet<>();
            for (DocumentBlock block : content.blocks()) {
                if (block.type() == BlockType.TABLE_CELL) {
                    if (writtenTables.add(block.tableIndex())) {
                        writeTable(document, content.blocks().stream()
                                .filter(cell -> cell.type() == BlockType.TABLE_CELL
                                        && cell.tableIndex() == block.tableIndex()).toList());
                    }
                    continue;
                }
                XWPFParagraph paragraph = document.createParagraph();
                if (block.type() == BlockType.HEADING) {
                    paragraph.setStyle("Heading1");
                } else if (block.type() == BlockType.LIST_ITEM) {
                    paragraph.setIndentationLeft(360);
                }
                paragraph.setAlignment(ParagraphAlignment.LEFT);
                paragraph.createRun().setText(block.text());
            }
            document.getProperties().getCoreProperties().setTitle(title);
            document.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new InvalidDocumentException("无法生成翻译后的 DOCX 文档", exception);
        }
    }

    private void writeTable(XWPFDocument document, List<DocumentBlock> cells) {
        if (cells.isEmpty()) {
            return;
        }
        int rows = cells.stream().mapToInt(DocumentBlock::rowIndex).max().orElse(0) + 1;
        int columns = cells.stream().mapToInt(DocumentBlock::columnIndex).max().orElse(0) + 1;
        XWPFTable table = document.createTable(rows, columns);
        for (DocumentBlock cell : cells) {
            table.getRow(cell.rowIndex()).getCell(cell.columnIndex()).setText(cell.text());
        }
    }
}
