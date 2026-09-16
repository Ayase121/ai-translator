package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Preserves every source PDF page, replacing only prose whose original position is reliable. */
public class PdfFixedLayoutDocxWriter {
    private static final Logger log = LoggerFactory.getLogger(PdfFixedLayoutDocxWriter.class);
    private static final int RENDER_DPI = 144;
    private static final float PIXELS_PER_POINT = RENDER_DPI / 72f;
    private static final float PAGE_IMAGE_SCALE = 0.985f;

    public WriteResult write(byte[] sourcePdf, DocumentContent original, DocumentContent translated,
                             Set<Integer> preserveInlineMath, String title) {
        if (original.blocks().size() != translated.blocks().size()) {
            log.warn("PDF fixed layout block count mismatch (source={}, translated={}); keeping source pages",
                    original.blocks().size(), translated.blocks().size());
        }
        int placed = 0;
        int retained = 0;
        int eligibleLines = 0;
        int coveredLines = 0;
        long started = System.nanoTime();
        try (PDDocument pdf = Loader.loadPDF(sourcePdf);
             XWPFDocument docx = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            PDRectangle lastDisplayedPage = null;
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                long pageStarted = System.nanoTime();
                List<IndexedBlock> sourceBlocks = blocksOnPage(original, page);
                List<IndexedBlock> translatedBlocks = blocksOnPage(translated, page);
                BufferedImage image = renderer.renderImageWithDPI(page - 1, RENDER_DPI, ImageType.RGB);
                BufferedImage sourceImage = new BufferedImage(image.getWidth(), image.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D sourceCopy = sourceImage.createGraphics();
                try {
                    sourceCopy.drawImage(image, 0, 0, null);
                } finally {
                    sourceCopy.dispose();
                }
                PDRectangle displayedPage = new PDRectangle(image.getWidth() / PIXELS_PER_POINT,
                        image.getHeight() / PIXELS_PER_POINT);
                lastDisplayedPage = displayedPage;
                int pagePlaced = 0;
                int pageRetained = 0;
                int pageEligibleLines = 0;
                int pageCoveredLines = 0;
                if (original.unpositionedPages().contains(page)) {
                    log.warn("PDF page {} kept unchanged (reason=TEXT_LINE_ALIGNMENT)", page);
                } else if (isRotatedOrCropped(pdf.getPage(page - 1))) {
                    pageRetained = countTranslatable(sourceBlocks);
                    log.warn("PDF page {} kept unchanged (reason=ROTATED_OR_CROPPED)", page);
                } else if (sameBlockStructure(sourceBlocks, translatedBlocks)) {
                    List<BlockLines> positioned = positionBlocks(pdf, page, sourceBlocks);
                    if (positioned != null) {
                        List<LineBox> allLines = positioned.stream().flatMap(block -> block.lines().stream()).toList();
                        boolean twoColumns = isTwoColumnPage(allLines, displayedPage.getWidth());
                        for (int index = 0; index < sourceBlocks.size(); index++) {
                            IndexedBlock source = sourceBlocks.get(index);
                            if (source.block().type() == BlockType.FORMULA
                                    || source.block().type() == BlockType.PROTECTED_LINE
                                    || source.block().type() == BlockType.REFERENCE) {
                                if (source.block().type() == BlockType.PROTECTED_LINE) {
                                    log.info("PDF page {} block {} kept at original location "
                                                    + "(reason=FORMULA_LINE_PROTECTED, lines={})",
                                            page, source.index(), positioned.get(index).lines().size());
                                }
                                continue;
                            }
                            List<LineBox> blockLines = positioned.get(index).lines();
                            boolean eligible = isEligibleBody(source.block().type(), blockLines, image,
                                    displayedPage.getWidth(), twoColumns);
                            if (eligible) {
                                pageEligibleLines += blockLines.size();
                            }
                            String replacement = translatedBlocks.get(index).block().text();
                            if (replacement == null || replacement.isBlank()
                                    || replacement.equals(source.block().text())
                                    || preserveInlineMath.contains(source.index())) {
                                pageRetained++;
                                log.warn("PDF page {} block {} kept at original location (reason={}, lines={})",
                                        page, source.index(), preserveInlineMath.contains(source.index())
                                                ? "FORMULA_LINE_PROTECTED" : "MODEL_FAILED_OR_UNTRANSLATED",
                                        blockLines.size());
                                continue;
                            }
                            PlacementReason reason = overlay(image, blockLines, allLines,
                                    replacement, displayedPage.getWidth(), twoColumns);
                            if (reason == PlacementReason.PLACED) {
                                pagePlaced++;
                                if (eligible) {
                                    pageCoveredLines += blockLines.size();
                                }
                            } else {
                                pageRetained++;
                                log.warn("PDF page {} block {} kept at original location (reason={}, sourceChars={}, "
                                                + "targetChars={}, lines={})", page, source.index(), reason,
                                        source.block().text().length(), replacement.length(),
                                        positioned.get(index).lines().size());
                            }
                        }
                        restoreProtectedLines(image, sourceImage, sourceBlocks, positioned);
                    } else {
                        pageRetained = countTranslatable(sourceBlocks);
                        log.warn("PDF page {} kept unchanged (reason=TEXT_POSITION_MISMATCH)", page);
                    }
                } else {
                    pageRetained = countTranslatable(sourceBlocks);
                    log.warn("PDF page {} kept unchanged (reason=BLOCK_MAPPING_MISMATCH)", page);
                }
                appendPageImage(docx, image, displayedPage, page, pdf.getNumberOfPages());
                image.flush();
                sourceImage.flush();
                placed += pagePlaced;
                retained += pageRetained;
                eligibleLines += pageEligibleLines;
                coveredLines += pageCoveredLines;
                log.info("PDF page {} fixed layout written (placed={}, retained={}, eligibleBodyLines={}, "
                                + "coveredBodyLines={}, protectedVariableLines={}, equationBlocks={}, elapsedMs={})",
                        page, pagePlaced, pageRetained, pageEligibleLines, pageCoveredLines,
                        sourceBlocks.stream().filter(block -> block.block().type() == BlockType.PROTECTED_LINE).count(),
                        sourceBlocks.stream().filter(block -> block.block().type() == BlockType.FORMULA).count(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pageStarted));
            }
            setPageSize(docx.getDocument().getBody().addNewSectPr(), lastDisplayedPage);
            docx.getProperties().getCoreProperties().setTitle(title);
            docx.write(output);
            log.info("PDF fixed layout DOCX finished (pages={}, placed={}, retained={}, eligibleBodyLines={}, "
                            + "coveredBodyLines={}, elapsedMs={})",
                    pdf.getNumberOfPages(), placed, retained, eligibleLines, coveredLines,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return new WriteResult(output.toByteArray(), placed, retained, eligibleLines, coveredLines);
        } catch (Exception exception) {
            throw new InvalidDocumentException("无法生成固定版式的 PDF 译稿", exception);
        }
    }

    private List<IndexedBlock> blocksOnPage(DocumentContent content, int page) {
        List<IndexedBlock> result = new ArrayList<>();
        for (int index = 0; index < content.blocks().size(); index++) {
            if (content.blocks().get(index).pageNumber() == page) {
                result.add(new IndexedBlock(index, content.blocks().get(index)));
            }
        }
        return result;
    }

    private boolean sameBlockStructure(List<IndexedBlock> original, List<IndexedBlock> translated) {
        if (original.size() != translated.size()) {
            return false;
        }
        for (int index = 0; index < original.size(); index++) {
            if (original.get(index).index() != translated.get(index).index()
                    || original.get(index).block().type() != translated.get(index).block().type()) {
                return false;
            }
        }
        return true;
    }

    private int countTranslatable(List<IndexedBlock> blocks) {
        return (int) blocks.stream().filter(block -> block.block().type() != BlockType.FORMULA
                && block.block().type() != BlockType.PROTECTED_LINE
                && block.block().type() != BlockType.REFERENCE).count();
    }

    private boolean isRotatedOrCropped(PDPage page) {
        PDRectangle crop = page.getCropBox();
        PDRectangle media = page.getMediaBox();
        return page.getRotation() != 0 || Math.abs(crop.getLowerLeftX() - media.getLowerLeftX()) > .1f
                || Math.abs(crop.getLowerLeftY() - media.getLowerLeftY()) > .1f
                || Math.abs(crop.getUpperRightX() - media.getUpperRightX()) > .1f
                || Math.abs(crop.getUpperRightY() - media.getUpperRightY()) > .1f;
    }

    private List<BlockLines> positionBlocks(PDDocument pdf, int page, List<IndexedBlock> blocks) throws Exception {
        PositionedTextStripper stripper = new PositionedTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String text = stripper.getText(pdf).strip();
        stripper.finishLastLine();
        String[] rawLines = text.split("\\R");
        List<LineBox> aligned = new ArrayList<>();
        int position = 0;
        for (String raw : rawLines) {
            String clean = raw.strip();
            if (clean.isEmpty()) {
                aligned.add(null);
                continue;
            }
            if (position >= stripper.lines.size() || !clean.equals(stripper.lines.get(position).text())) {
                return null;
            }
            aligned.add(stripper.lines.get(position++));
        }
        if (position != stripper.lines.size()) {
            return null;
        }
        List<BlockLines> result = new ArrayList<>();
        int cursor = 0;
        for (IndexedBlock indexed : blocks) {
            while (cursor < rawLines.length && rawLines[cursor].strip().isEmpty()) {
                cursor++;
            }
            StringBuilder gathered = new StringBuilder();
            List<LineBox> lines = new ArrayList<>();
            while (cursor < rawLines.length && !rawLines[cursor].strip().isEmpty()) {
                String next = rawLines[cursor].strip();
                if (!gathered.isEmpty() && gathered.charAt(gathered.length() - 1) != '-') {
                    gathered.append(' ');
                }
                gathered.append(next);
                lines.add(aligned.get(cursor++));
                if (gathered.toString().equals(indexed.block().text())) {
                    break;
                }
                if (!indexed.block().text().startsWith(gathered.toString())) {
                    return null;
                }
            }
            if (!gathered.toString().equals(indexed.block().text()) || lines.isEmpty()) {
                return null;
            }
            result.add(new BlockLines(lines));
        }
        return result;
    }

    private boolean isTwoColumnPage(List<LineBox> lines, float pageWidth) {
        long left = lines.stream().filter(line -> line.x() < pageWidth * .42f).count();
        long right = lines.stream().filter(line -> line.x() > pageWidth * .52f).count();
        return left >= 8 && right >= 8;
    }

    private boolean isEligibleBody(BlockType type, List<LineBox> lines, BufferedImage image,
                                   float pageWidth, boolean twoColumns) {
        if (type != BlockType.PARAGRAPH && type != BlockType.HEADING) {
            return false;
        }
        if (lines.isEmpty() || hasDiscontinuousLines(lines)
                || hasMixedColumnLines(lines, pageWidth)) {
            return false;
        }
        float left = lines.stream().map(LineBox::x).min(Float::compare).orElse(0f);
        float right = lines.stream().map(LineBox::right).max(Float::compare).orElse(0f);
        float top = lines.stream().map(LineBox::y).min(Float::compare).orElse(0f);
        float bottom = lines.stream().map(LineBox::bottom).max(Float::compare).orElse(0f);
        if (twoColumns && !(right < pageWidth * .51f || left > pageWidth * .49f)) {
            return false;
        }
        return !hasGraphicRule(image, left, top, right + 6f, bottom);
    }

    private void restoreProtectedLines(BufferedImage page, BufferedImage source,
                                       List<IndexedBlock> blocks, List<BlockLines> positioned) {
        for (int index = 0; index < blocks.size(); index++) {
            BlockType type = blocks.get(index).block().type();
            if (type != BlockType.FORMULA && type != BlockType.PROTECTED_LINE
                    && type != BlockType.REFERENCE) {
                continue;
            }
            for (LineBox line : positioned.get(index).lines()) {
                int left = Math.max(0, Math.round((line.x() - .5f) * PIXELS_PER_POINT));
                int top = Math.max(0, Math.round((line.y() - .5f) * PIXELS_PER_POINT));
                int right = Math.min(page.getWidth(), Math.round((line.right() + .5f) * PIXELS_PER_POINT));
                int bottom = Math.min(page.getHeight(), Math.round((line.bottom() + .5f) * PIXELS_PER_POINT));
                for (int y = top; y < bottom; y++) {
                    for (int x = left; x < right; x++) {
                        page.setRGB(x, y, source.getRGB(x, y));
                    }
                }
            }
        }
    }

    private PlacementReason overlay(BufferedImage image, List<LineBox> lines, List<LineBox> allLines,
                            String translation, float pageWidth, boolean twoColumns) {
        if (lines.isEmpty() || translation.length() > 4_000) {
            return PlacementReason.NO_POSITION;
        }
        if (hasMixedColumnLines(lines, pageWidth)) {
            return PlacementReason.CROSS_COLUMN;
        }
        float left = lines.stream().map(LineBox::x).min(Float::compare).orElse(0f);
        float right = lines.stream().map(LineBox::right).max(Float::compare).orElse(0f);
        float top = lines.stream().map(LineBox::y).min(Float::compare).orElse(0f);
        float bottom = lines.stream().map(LineBox::bottom).max(Float::compare).orElse(0f);
        boolean inLeftColumn = right < pageWidth * .51f;
        boolean inRightColumn = left > pageWidth * .49f;
        if (twoColumns && !inLeftColumn && !inRightColumn) {
            return PlacementReason.CROSS_COLUMN;
        }
        float columnRight = inLeftColumn && twoColumns ? pageWidth * .49f : pageWidth - 55f;
        // A translation may use blank space alongside the source text, but never extend into a
        // figure or table whose geometry the text extractor cannot see.
        float availableRight = Math.min(columnRight, right + 6f);
        if (left < 30 || top < 25 || bottom > image.getHeight() / PIXELS_PER_POINT - 20
                || availableRight - left < 35 || bottom - top < 7) {
            return PlacementReason.PAGE_BOUNDARY;
        }
        if (hasDiscontinuousLines(lines)) {
            return PlacementReason.LINE_DISCONTINUITY;
        }
        if (hasGraphicRule(image, left, top, availableRight, bottom)) {
            return PlacementReason.GRAPHIC_PROTECTED;
        }
        // Test only the row strips actually erased. A neighboring wrapped line can touch the
        // bounding box of this paragraph without touching any painted pixels.
        for (LineBox line : lines) {
            for (LineBox other : allLines) {
                if (!lines.contains(other)) {
                    float overlapX = Math.min(line.right() + 1, other.right())
                            - Math.max(line.x() - 1, other.x());
                    float overlapY = Math.min(line.bottom() + 1, other.bottom())
                            - Math.max(line.y() - 1, other.y());
                    if (overlapX > 3 && overlapY > 2) {
                        return PlacementReason.TRUE_COLLISION;
                    }
                }
            }
        }
        float sourceFont = (float) lines.stream().mapToDouble(LineBox::fontSize).average().orElse(9d);
        float fontPointSize = Math.min(12f, Math.max(8f, sourceFont));
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            float widthPixels = (availableRight - left - 2) * PIXELS_PER_POINT;
            float originalHeightPixels = (bottom - top + 2) * PIXELS_PER_POINT;
            float extendedBottom = blankSpaceBelow(image, lines, allLines, left, availableRight,
                    bottom, inLeftColumn, inRightColumn, twoColumns);
            List<String> wrapped = null;
            Font selected = null;
            int lineHeight = 0;
            for (float pointSize = fontPointSize; pointSize >= 7.5f; pointSize -= .5f) {
                Font candidate = new Font("SimHei", Font.PLAIN, 1).deriveFont(pointSize * PIXELS_PER_POINT);
                if (candidate.canDisplayUpTo(translation) >= 0) {
                    candidate = new Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(pointSize * PIXELS_PER_POINT);
                    if (candidate.canDisplayUpTo(translation) >= 0) {
                        continue;
                    }
                }
                graphics.setFont(candidate);
                FontMetrics metrics = graphics.getFontMetrics();
                List<String> candidateLines = wrap(translation, metrics, (int) widthPixels);
                int candidateLineHeight = Math.max(metrics.getHeight(), Math.round(pointSize * PIXELS_PER_POINT * 1.1f));
                int requiredHeight = metrics.getHeight()
                        + (candidateLines.size() - 1) * candidateLineHeight;
                if (!candidateLines.isEmpty() && (requiredHeight <= originalHeightPixels
                        || requiredHeight <= (extendedBottom - top) * PIXELS_PER_POINT)) {
                    selected = candidate;
                    wrapped = candidateLines;
                    lineHeight = candidateLineHeight;
                    break;
                }
            }
            if (selected == null) {
                return PlacementReason.TEXT_OVERFLOW;
            }
            graphics.setColor(Color.WHITE);
            for (LineBox line : lines) {
                int x = Math.max(0, Math.round((line.x() - 1) * PIXELS_PER_POINT));
                int y = Math.max(0, Math.round((line.y() - 1) * PIXELS_PER_POINT));
                int width = Math.min(image.getWidth() - x,
                        Math.round((line.right() - line.x() + 2) * PIXELS_PER_POINT));
                int height = Math.round((line.bottom() - line.y() + 2) * PIXELS_PER_POINT);
                graphics.fillRect(x, y, width, height);
            }
            graphics.setColor(Color.BLACK);
            graphics.setFont(selected);
            int baseline = Math.round((top + lines.get(0).fontSize() * .9f) * PIXELS_PER_POINT);
            int x = Math.round(left * PIXELS_PER_POINT);
            for (String line : wrapped) {
                graphics.drawString(line, x, baseline);
                baseline += lineHeight;
            }
            return PlacementReason.PLACED;
        } finally {
            graphics.dispose();
        }
    }

    private enum PlacementReason {
        PLACED, NO_POSITION, CROSS_COLUMN, PAGE_BOUNDARY, LINE_DISCONTINUITY,
        GRAPHIC_PROTECTED, TRUE_COLLISION, TEXT_OVERFLOW
    }

    private float blankSpaceBelow(BufferedImage image, List<LineBox> ownLines,
                                  List<LineBox> allLines, float left, float right, float bottom,
                                  boolean inLeftColumn, boolean inRightColumn, boolean twoColumns) {
        float limit = Math.min(image.getHeight() / PIXELS_PER_POINT - 20, bottom + 80);
        for (LineBox other : allLines) {
            if (ownLines.contains(other) || other.y() <= bottom + 2) {
                continue;
            }
            if (twoColumns && ((inLeftColumn && other.x() > image.getWidth() / PIXELS_PER_POINT * .49f)
                    || (inRightColumn && other.right() < image.getWidth() / PIXELS_PER_POINT * .51f))) {
                continue;
            }
            if (other.right() > left && other.x() < right) {
                limit = Math.min(limit, other.y() - 2);
            }
        }
        int x0 = Math.max(0, Math.round(left * PIXELS_PER_POINT));
        int x1 = Math.min(image.getWidth(), Math.round(right * PIXELS_PER_POINT));
        int y0 = Math.max(0, Math.round((bottom + 3) * PIXELS_PER_POINT));
        int y1 = Math.min(image.getHeight(), Math.round(limit * PIXELS_PER_POINT));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xff) < 245 || ((rgb >> 8) & 0xff) < 245
                        || (rgb & 0xff) < 245) {
                    return Math.max(bottom, y / PIXELS_PER_POINT - 2);
                }
            }
        }
        return Math.max(bottom, limit);
    }

    private boolean hasDiscontinuousLines(List<LineBox> lines) {
        for (int index = 1; index < lines.size(); index++) {
            LineBox before = lines.get(index - 1);
            LineBox after = lines.get(index);
            float step = after.y() - before.y();
            if (step < 0 || step > Math.max(30f, before.fontSize() * 2.7f)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMixedColumnLines(List<LineBox> lines, float pageWidth) {
        if (lines.stream().anyMatch(LineBox::largeHorizontalGap)) {
            return true;
        }
        boolean left = lines.stream().anyMatch(line -> line.x() < pageWidth * .42f
                && line.right() < pageWidth * .55f);
        boolean right = lines.stream().anyMatch(line -> line.x() > pageWidth * .48f);
        return left && right;
    }

    /** Table borders and figure strokes are invisible to PDFTextStripper, so inspect the source raster. */
    private boolean hasGraphicRule(BufferedImage image, float left, float top, float right, float bottom) {
        int x0 = Math.max(0, Math.round((left - 4) * PIXELS_PER_POINT));
        int x1 = Math.min(image.getWidth(), Math.round((right + 4) * PIXELS_PER_POINT));
        int y0 = Math.max(0, Math.round((top - 8) * PIXELS_PER_POINT));
        int y1 = Math.min(image.getHeight(), Math.round((bottom + 8) * PIXELS_PER_POINT));
        int minimumRun = Math.max(80, Math.round((x1 - x0) * .6f));
        int minimumColoredRun = Math.max(28, Math.round((x1 - x0) * .12f));
        int minimumPaleCoverage = Math.max(36, Math.round((x1 - x0) * .32f));
        int previousSmallRunX = -1;
        int smallGraphicRows = 0;
        for (int y = y0; y < y1; y++) {
            int run = 0;
            int coloredRun = 0;
            int paleRun = 0;
            int paleCoverage = 0;
            int smallRun = 0;
            int smallRunX = -1;
            for (int x = x0; x < x1; x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                int minimum = Math.min(red, Math.min(green, blue));
                int maximum = Math.max(red, Math.max(green, blue));
                if (maximum - minimum < 12 && minimum >= 195 && minimum < 235) {
                    paleRun++;
                } else {
                    if (paleRun >= 4) {
                        paleCoverage += paleRun;
                    }
                    paleRun = 0;
                }
                if (Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > 35
                        && Math.min(red, Math.min(green, blue)) < 240) {
                    if (++coloredRun >= minimumColoredRun) {
                        return true;
                    }
                } else {
                    coloredRun = 0;
                }
                if (red < 190 && green < 190 && blue < 190) {
                    if (++run >= minimumRun) {
                        return true;
                    }
                    if (++smallRun >= 20 && smallRunX < 0) {
                        smallRunX = x - smallRun + 1;
                    }
                } else {
                    run = 0;
                    smallRun = 0;
                }
            }
            if (paleRun >= 4) {
                paleCoverage += paleRun;
            }
            if (paleCoverage >= minimumPaleCoverage) {
                return true;
            }
            if (smallRunX >= 0 && previousSmallRunX >= 0
                    && Math.abs(smallRunX - previousSmallRunX) <= 6) {
                smallGraphicRows++;
            } else {
                smallGraphicRows = 0;
            }
            previousSmallRunX = smallRunX;
            if (smallGraphicRows >= 7) {
                return true;
            }
        }
        int minimumVerticalRun = Math.max(36, Math.round((y1 - y0) * .55f));
        for (int x = x0; x < x1; x++) {
            int run = 0;
            int coloredRun = 0;
            for (int y = y0; y < y1; y++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                if (Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > 35
                        && Math.min(red, Math.min(green, blue)) < 240) {
                    if (++coloredRun >= minimumVerticalRun) {
                        return true;
                    }
                } else {
                    coloredRun = 0;
                }
                if (((rgb >> 16) & 0xff) < 190 && ((rgb >> 8) & 0xff) < 190 && (rgb & 0xff) < 190) {
                    if (++run >= minimumVerticalRun) {
                        return true;
                    }
                } else {
                    run = 0;
                }
            }
        }
        return false;
    }

    private List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int width = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n') {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                    width = 0;
                }
                continue;
            }
            int nextWidth = metrics.charWidth(codePoint);
            if (width + nextWidth > maxWidth && !current.isEmpty()) {
                lines.add(current.toString().stripTrailing());
                current.setLength(0);
                width = 0;
            }
            if (current.isEmpty() && Character.isWhitespace(codePoint)) {
                continue;
            }
            current.appendCodePoint(codePoint);
            width += nextWidth;
        }
        if (!current.isEmpty()) {
            lines.add(current.toString().stripTrailing());
        }
        return lines;
    }

    private void setPageSize(CTSectPr section, PDRectangle page) {
        CTPageSz size = section.addNewPgSz();
        size.setW(BigInteger.valueOf(Math.round(page.getWidth() * 20)));
        size.setH(BigInteger.valueOf(Math.round(page.getHeight() * 20)));
        CTPageMar margins = section.addNewPgMar();
        margins.setTop(BigInteger.ZERO);
        margins.setBottom(BigInteger.ZERO);
        margins.setLeft(BigInteger.ZERO);
        margins.setRight(BigInteger.ZERO);
        margins.setHeader(BigInteger.ZERO);
        margins.setFooter(BigInteger.ZERO);
    }

    private void appendPageImage(XWPFDocument document, BufferedImage image, PDRectangle page,
                                 int pageNumber, int pageCount)
            throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(0);
        paragraph.setSpacingAfter(0);
        paragraph.setSpacingBetween(1.0);
        XWPFRun run = paragraph.createRun();
        run.addPicture(new ByteArrayInputStream(png.toByteArray()), XWPFDocument.PICTURE_TYPE_PNG,
                "pdf-page-" + pageNumber + ".png", Units.toEMU(page.getWidth() * PAGE_IMAGE_SCALE),
                Units.toEMU(page.getHeight() * PAGE_IMAGE_SCALE));
        if (pageNumber < pageCount) {
            setPageSize(paragraph.getCTP().addNewPPr().addNewSectPr(), page);
        }
    }

    public record WriteResult(byte[] bytes, int placedBlocks, int retainedBlocks,
                              int eligibleLines, int coveredLines) {
    }

    private record IndexedBlock(int index, DocumentBlock block) {
    }

    private record BlockLines(List<LineBox> lines) {
    }

    private record LineBox(String text, float x, float y, float right, float bottom,
                           float fontSize, boolean largeHorizontalGap) {
    }

    private static final class PositionedTextStripper extends PDFTextStripper {
        private final List<LineBox> lines = new ArrayList<>();
        private final List<TextPosition> positions = new ArrayList<>();
        private final StringBuilder currentText = new StringBuilder();

        private PositionedTextStripper() throws java.io.IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws java.io.IOException {
            currentText.append(text);
            positions.addAll(textPositions);
            super.writeString(text, textPositions);
        }

        @Override
        protected void writeWordSeparator() throws java.io.IOException {
            currentText.append(' ');
            super.writeWordSeparator();
        }

        @Override
        protected void writeLineSeparator() throws java.io.IOException {
            finishLastLine();
            super.writeLineSeparator();
        }

        private void finishLastLine() {
            String text = currentText.toString().strip();
            if (!text.isEmpty() && !positions.isEmpty()) {
                float left = Float.POSITIVE_INFINITY;
                float top = Float.POSITIVE_INFINITY;
                float right = Float.NEGATIVE_INFINITY;
                float bottom = Float.NEGATIVE_INFINITY;
                float size = 0;
                boolean largeGap = false;
                TextPosition previous = null;
                for (TextPosition position : positions) {
                    if (previous != null && position.getXDirAdj()
                            - (previous.getXDirAdj() + previous.getWidthDirAdj()) > 55f) {
                        largeGap = true;
                    }
                    left = Math.min(left, position.getXDirAdj());
                    // PDFBox reports the baseline. The painted glyphs begin above it; using
                    // that baseline as the rectangle top left English visible under Chinese.
                    top = Math.min(top, position.getYDirAdj() - position.getFontSizeInPt() * .9f);
                    right = Math.max(right, position.getXDirAdj() + position.getWidthDirAdj());
                    bottom = Math.max(bottom, position.getYDirAdj() + position.getFontSizeInPt() * .25f);
                    size += position.getFontSizeInPt();
                    previous = position;
                }
                lines.add(new LineBox(text, left, top, right, bottom,
                        size / positions.size(), largeGap));
            }
            positions.clear();
            currentText.setLength(0);
        }
    }
}
