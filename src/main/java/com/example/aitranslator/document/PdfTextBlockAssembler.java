package com.example.aitranslator.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Groups wrapped PDF text lines without asking the model to translate equations. */
final class PdfTextBlockAssembler {
    private static final int MAX_PROSE_CHARACTERS = 2_400;
    private static final Pattern EQUATION_START = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]{0,12}\\s*[=≈≤≥].*");
    private static final Pattern TABLE_ROW = Pattern.compile(
            "^[A-Za-z0-9+*.\\-]+(?:\\s+\\d+(?:\\.\\d+)?){3,}.*");
    private static final Pattern SECTION_HEADING = Pattern.compile(
            "^\\d+(?:\\.\\d+)*\\s+[A-Z][A-Za-z ,:;-]{2,75}$");

    private PdfTextBlockAssembler() {
    }

    static List<DocumentBlock> assemble(String text) {
        List<DocumentBlock> blocks = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        StringBuilder formula = new StringBuilder();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                flushProse(blocks, prose);
                flushFormula(blocks, formula);
                continue;
            }
            if (isFormula(line)) {
                flushProse(blocks, prose);
                appendLine(formula, line);
                continue;
            }
            flushFormula(blocks, formula);
            if (PdfProtectedSpan.contains(line)) {
                flushProse(blocks, prose);
                blocks.add(new DocumentBlock(BlockType.PROTECTED_LINE, line, 0, 0, -1));
                continue;
            }
            if (isHeading(line)) {
                flushProse(blocks, prose);
                blocks.add(new DocumentBlock(BlockType.HEADING, line, 0, 0, -1));
                continue;
            }
            appendLine(prose, line);
            if (prose.length() >= MAX_PROSE_CHARACTERS && endsSentence(prose)) {
                flushProse(blocks, prose);
            }
        }
        flushProse(blocks, prose);
        flushFormula(blocks, formula);
        return blocks;
    }

    static List<DocumentBlock> assembleVisual(String text, List<PdfVisualLine> positions,
                                               float pageWidth) {
        StringBuilder visuallySeparated = new StringBuilder();
        PdfVisualLine previous = null;
        int position = 0;
        for (String raw : text.strip().split("\\R")) {
            String clean = raw.strip();
            if (clean.isEmpty()) {
                visuallySeparated.append('\n');
                previous = null;
                continue;
            }
            if (position >= positions.size() || !clean.equals(positions.get(position).text())) {
                return List.of();
            }
            PdfVisualLine current = positions.get(position++);
            if (previous != null && visualBoundary(previous, current, pageWidth)) {
                visuallySeparated.append('\n');
            }
            visuallySeparated.append(clean).append('\n');
            previous = current;
        }
        if (position != positions.size()) {
            return List.of();
        }
        return assemble(visuallySeparated.toString());
    }

    private static boolean visualBoundary(PdfVisualLine before, PdfVisualLine after,
                                          float pageWidth) {
        if (after.y() < before.y() - 5f || Math.abs(after.x() - before.x()) > pageWidth * .18f) {
            return true;
        }
        float step = after.y() - before.y();
        if (step > Math.max(18f, before.fontSize() * 1.9f)) {
            return true;
        }
        return after.x() - before.x() > 7f
                && before.text().matches(".*[.!?]$")
                && after.text().matches("^[A-Z].*");
    }

    private static boolean isFormula(String line) {
        if (TABLE_ROW.matcher(line).matches()) {
            return true;
        }
        if (EQUATION_START.matcher(line).matches()) {
            return true;
        }
        if (line.length() <= 45 && line.matches(".*[∑∫∀∂√≈≤≥].*")) {
            return true;
        }
        if (line.contains("∈") && wordCount(line) <= 5) {
            return true;
        }
        if (line.length() <= 35 && line.matches(".*[=+*/^|].*") && !line.contains(" ")) {
            return true;
        }
        return line.matches("^\\(\\d+\\)$") && !line.contains(".");
    }

    private static int wordCount(String line) {
        return line.strip().split("\\s+").length;
    }

    private static boolean isHeading(String line) {
        return line.matches("^(Abstract|Introduction|Conclusion|Conclusions|Limitations)$")
                || line.equals("References")
                || line.matches("^A\\s+Appendix\\b.*")
                || line.matches("^(Figure|Table)\\s+\\d+\\b.*")
                || SECTION_HEADING.matcher(line).matches();
    }

    private static boolean endsSentence(StringBuilder text) {
        return text.length() > 0 && ".?!。！？".indexOf(text.charAt(text.length() - 1)) >= 0;
    }

    private static void appendLine(StringBuilder target, String line) {
        if (target.length() > 0 && target.charAt(target.length() - 1) != '-') {
            target.append(' ');
        }
        target.append(line);
    }

    private static void flushProse(List<DocumentBlock> blocks, StringBuilder text) {
        if (!text.isEmpty()) {
            blocks.add(DocumentBlock.paragraph(text.toString()));
            text.setLength(0);
        }
    }

    private static void flushFormula(List<DocumentBlock> blocks, StringBuilder text) {
        if (!text.isEmpty()) {
            blocks.add(new DocumentBlock(BlockType.FORMULA, text.toString(), 0, 0, -1));
            text.setLength(0);
        }
    }
}
