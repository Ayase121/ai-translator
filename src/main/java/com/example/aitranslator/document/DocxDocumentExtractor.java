package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public class DocxDocumentExtractor implements DocumentExtractor {

    @Override
    public DocumentContent extract(byte[] bytes) {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            int tableIndex = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    addParagraph(blocks, paragraph);
                } else if (element instanceof XWPFTable table) {
                    addTable(blocks, table, tableIndex++);
                }
            }
        } catch (Exception exception) {
            throw new InvalidDocumentException("无法解析 DOCX 文档", exception);
        }
        if (blocks.isEmpty()) {
            throw new InvalidDocumentException("文档中没有可翻译的文本");
        }
        return new DocumentContent(blocks);
    }

    private void addParagraph(List<DocumentBlock> blocks, XWPFParagraph paragraph) {
        String text = paragraph.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        String style = paragraph.getStyle();
        BlockType type = style != null && style.toLowerCase().startsWith("heading")
                ? BlockType.HEADING
                : paragraph.getNumID() != null ? BlockType.LIST_ITEM : BlockType.PARAGRAPH;
        blocks.add(new DocumentBlock(type, text, 0, 0, -1));
    }

    private void addTable(List<DocumentBlock> blocks, XWPFTable table, int tableIndex) {
        for (int rowIndex = 0; rowIndex < table.getRows().size(); rowIndex++) {
            XWPFTableRow row = table.getRow(rowIndex);
            List<XWPFTableCell> cells = row.getTableCells();
            for (int columnIndex = 0; columnIndex < cells.size(); columnIndex++) {
                String text = cells.get(columnIndex).getText().strip();
                if (!text.isEmpty()) {
                    blocks.add(new DocumentBlock(BlockType.TABLE_CELL, text, rowIndex, columnIndex, tableIndex));
                }
            }
        }
    }
}
