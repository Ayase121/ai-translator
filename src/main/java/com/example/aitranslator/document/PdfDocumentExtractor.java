package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

public class PdfDocumentExtractor implements DocumentExtractor {
    private static final Logger log = LoggerFactory.getLogger(PdfDocumentExtractor.class);

    private final OcrService ocrService;
    private final int minimumTextCharacters;
    private final int maxPages;
    private final int dpi;

    public PdfDocumentExtractor(OcrService ocrService, int minimumTextCharacters, int maxPages, int dpi) {
        this.ocrService = ocrService;
        this.minimumTextCharacters = minimumTextCharacters;
        this.maxPages = maxPages;
        this.dpi = dpi;
    }

    @Override
    public DocumentContent extract(byte[] bytes) {
        List<DocumentBlock> blocks = new ArrayList<>();
        Set<Integer> ocrPages = new HashSet<>();
        Set<Integer> unpositionedPages = new HashSet<>();
        boolean inReferences = false;
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new InvalidDocumentException("暂不支持加密 PDF");
            }
            if (document.getNumberOfPages() > maxPages) {
                throw new InvalidDocumentException("PDF 不能超过 " + maxPages + " 页");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                long pageStarted = System.nanoTime();
                long ocrMillis = 0;
                PdfVisualTextStripper stripper = new PdfVisualTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).strip();
                boolean usedOcr = shouldUseOcr(text);
                if (usedOcr) {
                    ocrPages.add(page);
                    long ocrStarted = System.nanoTime();
                    BufferedImage image = renderer.renderImageWithDPI(page - 1, dpi, ImageType.RGB);
                    try {
                        String ocrText = ocrService.recognize(image);
                        text = ocrText == null ? "" : ocrText.strip();
                    } catch (RuntimeException exception) {
                        // This page has no reliable text coordinates. The fixed-layout output
                        // retains the original image, so a bad OCR result must not discard
                        // translation from the other pages.
                        log.warn("PDF page {} kept unchanged (reason=OCR_FAILED, error={})",
                                page, exception.getClass().getSimpleName());
                        text = "";
                    } finally {
                        image.flush();
                        ocrMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ocrStarted);
                    }
                }
                int pageNumber = page;
                List<DocumentBlock> pageBlocks = new ArrayList<>();
                List<DocumentBlock> assembled = usedOcr
                        ? PdfTextBlockAssembler.assemble(text)
                        : PdfTextBlockAssembler.assembleVisual(text, stripper.lines(),
                                document.getPage(page - 1).getCropBox().getWidth());
                if (!usedOcr && assembled.isEmpty() && !text.isBlank()) {
                    unpositionedPages.add(page);
                    log.warn("PDF page {} kept unchanged (reason=TEXT_LINE_ALIGNMENT)", page);
                }
                for (DocumentBlock block : assembled) {
                    if (block.text().matches("(?i)^A\\s+Appendix\\b.*")) {
                        inReferences = false;
                    }
                    BlockType type = inReferences && block.type() == BlockType.PARAGRAPH
                            ? BlockType.REFERENCE : block.type();
                    pageBlocks.add(new DocumentBlock(type, block.text(), block.rowIndex(),
                            block.columnIndex(), block.tableIndex(), pageNumber));
                    if (block.type() == BlockType.HEADING
                            && block.text().equalsIgnoreCase("References")) {
                        inReferences = true;
                    }
                }
                blocks.addAll(pageBlocks);
                log.info("PDF page {} extracted via {} (characters={}, blocks={}, formulas={}, ocrMs={}, elapsedMs={})",
                        page, usedOcr ? "OCR" : "TEXT_LAYER", text.length(), pageBlocks.size(),
                        pageBlocks.stream().filter(block -> block.type() == BlockType.FORMULA).count(),
                        ocrMillis, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pageStarted));
            }
        } catch (InvalidDocumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidDocumentException("无法解析 PDF 文档", exception);
        }
        if (blocks.isEmpty()) {
            throw new InvalidDocumentException("PDF 中没有识别到可翻译文本");
        }
        return new DocumentContent(blocks, ocrPages, unpositionedPages);
    }

    private boolean shouldUseOcr(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s", "");
        if (compact.length() < minimumTextCharacters || compact.indexOf('\uFFFD') >= 0) {
            return true;
        }
        long controls = compact.chars().filter(character -> Character.isISOControl(character)).count();
        return controls > 0;
    }

}
