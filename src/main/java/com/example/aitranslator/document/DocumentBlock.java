package com.example.aitranslator.document;

public record DocumentBlock(BlockType type, String text, int rowIndex, int columnIndex,
                            int tableIndex, int pageNumber) {

    public DocumentBlock(BlockType type, String text, int rowIndex, int columnIndex, int tableIndex) {
        this(type, text, rowIndex, columnIndex, tableIndex, -1);
    }

    public DocumentBlock(BlockType type, String text, int rowIndex, int columnIndex) {
        this(type, text, rowIndex, columnIndex, type == BlockType.TABLE_CELL ? 0 : -1, -1);
    }

    public static DocumentBlock paragraph(String text) {
        return new DocumentBlock(BlockType.PARAGRAPH, text, 0, 0, -1, -1);
    }
}
