package com.example.aitranslator.service;

import com.example.aitranslator.exception.InvalidTranslationRequestException;
import com.example.aitranslator.ai.TranslationModelGateway;
import com.example.aitranslator.ai.TranslationModelRequest;
import com.example.aitranslator.ai.StyledTextSegment;
import com.example.aitranslator.document.DocumentBlock;
import com.example.aitranslator.document.DocumentContent;
import com.example.aitranslator.document.BlockType;
import com.example.aitranslator.document.DocumentExtractorRegistry;
import com.example.aitranslator.document.DocumentFileValidator;
import com.example.aitranslator.document.DocxDocumentWriter;
import com.example.aitranslator.document.PdfFixedLayoutDocxWriter;
import com.example.aitranslator.document.DocxParagraph;
import com.example.aitranslator.document.DocxTranslationDocument;
import com.example.aitranslator.exception.InvalidDocumentException;
import com.example.aitranslator.domain.Language;
import com.example.aitranslator.exception.InvalidAiResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class DocumentTranslationService {
    private static final Logger log = LoggerFactory.getLogger(DocumentTranslationService.class);
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]{2,}");

    private final DocumentFileValidator validator;
    private final DocumentExtractorRegistry registry;
    private final DocxDocumentWriter writer;
    private final PdfFixedLayoutDocxWriter pdfWriter;
    private final DocumentTranslationPipeline pipeline;
    private final int maxCharacters;

    @Autowired
    public DocumentTranslationService(
            DocumentFileValidator validator,
            DocumentExtractorRegistry registry,
            DocxDocumentWriter writer,
            PdfFixedLayoutDocxWriter pdfWriter,
            DocumentTranslationPipeline pipeline,
            @Value("${app.document.max-characters:100000}") int maxCharacters) {
        this.validator = validator;
        this.registry = registry;
        this.writer = writer;
        this.pdfWriter = pdfWriter;
        this.pipeline = pipeline;
        this.maxCharacters = maxCharacters;
    }

    public DocumentTranslationService(DocumentFileValidator validator, DocumentExtractorRegistry registry,
                                      DocxDocumentWriter writer, DocumentTranslationPipeline pipeline,
                                      int maxCharacters) {
        this(validator, registry, writer, new PdfFixedLayoutDocxWriter(), pipeline, maxCharacters);
    }

    /** Compatibility constructor for callers that still provide the legacy text gateway. */
    public DocumentTranslationService(
            DocumentFileValidator validator,
            DocumentExtractorRegistry registry,
            DocxDocumentWriter writer,
            TranslationModelGateway gateway,
            int maxCharacters,
            int chunkSize) {
        this(validator, registry, writer, legacyPipeline(gateway), maxCharacters);
    }

    private static DocumentTranslationPipeline legacyPipeline(TranslationModelGateway gateway) {
        return new DocumentTranslationPipeline(request -> List.of(), request -> {
            Map<String, String> result = new java.util.LinkedHashMap<>();
            request.currentSegments().forEach(segment -> result.put(segment.id(), gateway.translate(
                    new TranslationModelRequest(segment.sourceText(), request.sourceLanguage(), request.targetLanguage()))));
            return result;
        }, Integer.MAX_VALUE, 2_000);
    }

    public TranslatedDocument translate(MultipartFile file, Language source, Language target) {
        return translate(file, source, target,
                new DocumentTranslationOptions(null, null, List.of()));
    }

    public TranslatedDocument translate(MultipartFile file, Language source, Language target,
                                       DocumentTranslationOptions options) {
        long totalStarted = System.nanoTime();
        if (target == Language.AUTO || source != Language.AUTO && source == target) {
            throw new InvalidTranslationRequestException("源语言和目标语言设置无效");
        }
        String extension = validator.validate(file);
        byte[] sourceBytes;
        try {
            sourceBytes = file.getBytes();
        } catch (Exception exception) {
            throw new InvalidDocumentException("无法读取上传文件", exception);
        }
        if (extension.equals("docx")) {
            long extractionStarted = System.nanoTime();
            DocxTranslationDocument original = DocxTranslationDocument.parse(sourceBytes);
            List<TranslationParagraph> paragraphs = original.paragraphs().stream()
                    .map(this::toTranslationParagraph).toList();
            validateCharacterCount(paragraphs);
            long extractionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - extractionStarted);
            long modelStarted = System.nanoTime();
            DocumentTranslationResult result = pipeline.translateWordBatched(paragraphs, source, target, options);
            long modelMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - modelStarted);
            int totalSegments = paragraphs.stream().mapToInt(paragraph -> paragraph.segments().size()).sum();
            int translatedSegments = result.translations().values().stream()
                    .mapToInt(Map::size).sum();
            boolean partial = translatedSegments < totalSegments;
            long writeStarted = System.nanoTime();
            byte[] written = original.write(result.translations());
            log.info("DOCX translation completed (paragraphs={}, translatedSegments={}, retainedSegments={}, "
                            + "extractionMs={}, modelMs={}, writeMs={}, totalMs={})",
                    paragraphs.size(), translatedSegments, totalSegments - translatedSegments,
                    extractionMillis, modelMillis,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writeStarted),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStarted));
            return new TranslatedDocument(translatedFilename(file.getOriginalFilename()),
                    written, partial,
                    totalSegments, translatedSegments);
        }

        long extractionStarted = System.nanoTime();
        DocumentContent content;
        try {
            content = registry.get(extension).extract(sourceBytes);
        } catch (InvalidDocumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidDocumentException("无法读取上传文件", exception);
        }
        List<TranslationParagraph> paragraphs = new ArrayList<>();
        boolean pdf = extension.equals("pdf");
        if (pdf) {
            log.info("PDF extraction stage finished (blocks={}, elapsedMs={})", content.blocks().size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - extractionStarted));
        }
        if (pdf && content.characterCount() > maxCharacters) {
            throw new InvalidDocumentException("文档可翻译文本不能超过 " + maxCharacters + " 个字符");
        }
        Map<Integer, PdfMathTextProtector.ProtectedText> protectedPdfBlocks = new HashMap<>();
        Set<Integer> preserveInlineMath = new HashSet<>();
        for (int index = 0; index < content.blocks().size(); index++) {
            DocumentBlock block = content.blocks().get(index);
            if (pdf && content.ocrPages().contains(block.pageNumber())) {
                log.warn("PDF block {} retained at original location (reason=OCR_POSITION_UNRELIABLE)",
                        blockId(block, index));
                continue;
            }
            if (pdf && (block.type() == BlockType.FORMULA || block.type() == BlockType.PROTECTED_LINE
                    || block.type() == BlockType.REFERENCE)) {
                continue;
            }
            String modelText = block.text();
            if (pdf) {
                PdfMathTextProtector.ProtectedText protectedText = PdfMathTextProtector.protect(modelText);
                protectedPdfBlocks.put(index, protectedText);
                if (!protectedText.originals().isEmpty()) {
                    preserveInlineMath.add(index);
                }
                modelText = protectedText.masked();
            }
            paragraphs.add(new TranslationParagraph(blockId(block, index),
                    List.of(new StyledTextSegment(segmentId(block, index), modelText, block.type().name()))));
        }
        if (pdf) {
            log.info("PDF translation prepared (blocks={}, translated={}, formulasAndOcrSkipped={}, ocrPages={})",
                    content.blocks().size(), paragraphs.size(), content.blocks().size() - paragraphs.size(),
                    content.ocrPages().size());
        }
        validateCharacterCount(paragraphs);
        if (paragraphs.isEmpty()) {
            throw new InvalidDocumentException(pdf ? "PDF 中没有可定位的正文" : "文档中没有可翻译正文");
        }
        DocumentTranslationResult result = pdf ? pipeline.translatePdfBatched(paragraphs, source, target, options)
                : pipeline.translate(paragraphs, source, target, options);
        List<DocumentBlock> translatedBlocks = new ArrayList<>();
        int modelRetained = 0;
        for (int index = 0; index < content.blocks().size(); index++) {
            DocumentBlock block = content.blocks().get(index);
            String translated = block.text();
            if (!pdf || block.type() != BlockType.FORMULA && block.type() != BlockType.PROTECTED_LINE
                    && block.type() != BlockType.REFERENCE
                    && !content.ocrPages().contains(block.pageNumber())) {
                Map<String, String> paragraphResult = result.translations().get(blockId(block, index));
                String modelTranslation = paragraphResult == null ? null : paragraphResult.get(segmentId(block, index));
                if (modelTranslation == null) {
                    if (!pdf) {
                        throw new InvalidAiResponseException("模型缺少文档段落翻译");
                    }
                    modelRetained++;
                    log.warn("PDF block {} retained at original location (reason=MODEL_BATCH_FAILED)",
                            blockId(block, index));
                } else {
                    translated = modelTranslation;
                }
                if (pdf && modelTranslation != null) {
                    PdfMathTextProtector.ProtectedText protectedText = protectedPdfBlocks.get(index);
                    var restored = protectedText.restore(translated);
                    if (restored.isEmpty()) {
                        log.warn("PDF protected expression was changed by model for {}; retaining source text "
                                        + "(reason=PROTECTED_MARKER_MISSING)",
                                blockId(block, index));
                        translated = block.text();
                        modelRetained++;
                    } else {
                        translated = restored.get();
                    }
                    if (!translated.equals(block.text())
                            && !hasSubstantiveTargetText(block.text(), translated, target)) {
                        log.warn("PDF block {} retained at original location (reason=TARGET_TEXT_INSUFFICIENT)",
                                blockId(block, index));
                        translated = block.text();
                        modelRetained++;
                    }
                }
            }
            translatedBlocks.add(new DocumentBlock(block.type(), translated,
                    block.rowIndex(), block.columnIndex(), block.tableIndex(), block.pageNumber()));
        }
        String filename = translatedFilename(file.getOriginalFilename());
        if (pdf) {
            long layoutStarted = System.nanoTime();
            PdfFixedLayoutDocxWriter.WriteResult written = pdfWriter.write(sourceBytes, content,
                    new DocumentContent(translatedBlocks), preserveInlineMath, filename);
            if (written.placedBlocks() == 0) {
                throw new InvalidAiResponseException("模型未译出可安全放入原页的 PDF 正文");
            }
            log.info("PDF translation completed (placed={}, retained={}, modelRetained={}, layoutMs={}, totalMs={})",
                    written.placedBlocks(), written.retainedBlocks(), modelRetained,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - layoutStarted),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStarted));
            return new TranslatedDocument(filename, written.bytes(),
                    written.retainedBlocks() > 0 || written.coveredLines() < written.eligibleLines()
                            || !content.ocrPages().isEmpty() || !content.unpositionedPages().isEmpty(),
                    written.eligibleLines(), written.coveredLines());
        }
        return new TranslatedDocument(filename, writer.write(new DocumentContent(translatedBlocks), filename));
    }

    private TranslationParagraph toTranslationParagraph(DocxParagraph paragraph) {
        return new TranslationParagraph(paragraph.id(), paragraph.segments().stream()
                .map(segment -> new StyledTextSegment(segment.id(), segment.sourceText(), segment.styleSignature()))
                .toList());
    }

    private String blockId(DocumentBlock block, int index) {
        return block.pageNumber() > 0 ? "pdf-page-" + block.pageNumber() + "#block-" + index
                : "block-" + index;
    }

    private String segmentId(DocumentBlock block, int index) {
        return block.pageNumber() > 0 ? blockId(block, index) + "#segment-" + index
                : "segment-" + index;
    }

    private void validateCharacterCount(List<TranslationParagraph> paragraphs) {
        int count = paragraphs.stream().mapToInt(paragraph -> paragraph.sourceText().length()).sum();
        if (count > maxCharacters) {
            throw new InvalidDocumentException("文档可翻译文本不能超过 " + maxCharacters + " 个字符");
        }
    }

    private boolean hasTargetScript(String text, Language target) {
        return switch (target) {
            case ZH_CN -> text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.HAN);
            case JA -> text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.KATAKANA || Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.HAN);
            case KO -> text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.HANGUL);
            case RU -> text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.CYRILLIC);
            case AR -> text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.ARABIC);
            default -> true;
        };
    }

    private boolean hasSubstantiveTargetText(String source, String translated, Language target) {
        if (!hasTargetScript(translated, target)) {
            return false;
        }
        if (target != Language.ZH_CN) {
            return true;
        }
        int sourceWords = (int) LATIN_WORD.matcher(source).results().count();
        int untranslatedWords = (int) LATIN_WORD.matcher(translated).results().count();
        long han = translated.codePoints().filter(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN).count();
        return han >= Math.max(2, Math.min(20, sourceWords / 5))
                && (sourceWords < 8 || untranslatedWords < sourceWords * .9f);
    }

    private String translatedFilename(String original) {
        String safe = original == null ? "document" : original.replaceAll("[\\r\\n\\\\/:*?\"<>|]", "_");
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        return base + "-translated.docx";
    }
}
