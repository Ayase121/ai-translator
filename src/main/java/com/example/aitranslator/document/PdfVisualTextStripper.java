package com.example.aitranslator.document;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Captures the same PDFBox lines as the extracted text, with their rendered coordinates. */
final class PdfVisualTextStripper extends PDFTextStripper {
    private final List<PdfVisualLine> lines = new ArrayList<>();
    private final List<TextPosition> positions = new ArrayList<>();
    private final StringBuilder currentText = new StringBuilder();

    PdfVisualTextStripper() throws IOException {
        super();
    }

    List<PdfVisualLine> lines() {
        finishLastLine();
        return List.copyOf(lines);
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        currentText.append(text);
        positions.addAll(textPositions);
        super.writeString(text, textPositions);
    }

    @Override
    protected void writeWordSeparator() throws IOException {
        currentText.append(' ');
        super.writeWordSeparator();
    }

    @Override
    protected void writeLineSeparator() throws IOException {
        finishLastLine();
        super.writeLineSeparator();
    }

    private void finishLastLine() {
        String text = currentText.toString().strip();
        if (!text.isEmpty() && !positions.isEmpty()) {
            float left = Float.POSITIVE_INFINITY;
            float top = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY;
            float size = 0;
            for (TextPosition position : positions) {
                left = Math.min(left, position.getXDirAdj());
                top = Math.min(top, position.getYDirAdj());
                right = Math.max(right, position.getXDirAdj() + position.getWidthDirAdj());
                size += position.getFontSizeInPt();
            }
            lines.add(new PdfVisualLine(text, left, top, right, size / positions.size()));
        }
        positions.clear();
        currentText.setLength(0);
    }
}
