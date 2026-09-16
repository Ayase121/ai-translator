package com.example.aitranslator.service;

import com.example.aitranslator.exception.InvalidTranslationRequestException;
import com.example.aitranslator.ai.GlossaryEntry;
import com.example.aitranslator.exception.InvalidAiResponseException;
import com.example.aitranslator.ai.ParagraphTranslationGateway;
import com.example.aitranslator.ai.ParagraphTranslationRequest;
import com.example.aitranslator.ai.ParagraphRequestMetrics;
import com.example.aitranslator.ai.StyledTextSegment;
import com.example.aitranslator.ai.TerminologyModelGateway;
import com.example.aitranslator.ai.TerminologyRequest;
import com.example.aitranslator.domain.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class DocumentTranslationPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentTranslationPipeline.class);
    private static final int PDF_BATCH_BLOCKS = 4;
    private static final int PDF_BATCH_CHARACTERS = 2_400;
    private static final Pattern PDF_SENTENCE_END = Pattern.compile("[.!?。！？](?=\\s|$)");
    private static final int PDF_DOCUMENT_CONCURRENCY = 2;
    private static final int DOCX_BATCH_PARAGRAPHS = 4;
    private static final int DOCX_BATCH_CHARACTERS = 2_400;
    private static final int DOCX_BATCH_SEGMENTS = 16;
    private static final int DOCX_SOURCE_CONTEXT_CHARACTERS = 400;
    private static final Semaphore DOCUMENT_GLOBAL_MODEL_SLOTS = new Semaphore(8);

    private final TerminologyModelGateway terminologyGateway;
    private final ParagraphTranslationGateway paragraphGateway;
    private final int glossaryChunkSize;
    private final int contextMaxCharacters;

    public DocumentTranslationPipeline(TerminologyModelGateway terminologyGateway,
                                       ParagraphTranslationGateway paragraphGateway,
                                       int glossaryChunkSize,
                                       int contextMaxCharacters) {
        this.terminologyGateway = terminologyGateway;
        this.paragraphGateway = paragraphGateway;
        this.glossaryChunkSize = Math.max(1, glossaryChunkSize);
        this.contextMaxCharacters = Math.max(0, contextMaxCharacters);
    }

    public DocumentTranslationResult translate(List<TranslationParagraph> paragraphs,
                                               Language sourceLanguage,
                                               Language targetLanguage,
                                               DocumentTranslationOptions options) {
        if (paragraphs == null || paragraphs.isEmpty()) {
            throw new InvalidTranslationRequestException("文档中没有可翻译的文本");
        }
        List<GlossaryEntry> glossary = extractGlossary(paragraphs, sourceLanguage, targetLanguage, options);
        Map<String, Map<String, String>> translations = new LinkedHashMap<>();
        Deque<String> previousTranslations = new ArrayDeque<>();
        InvalidAiResponseException lastFailure = null;
        for (TranslationParagraph paragraph : paragraphs) {
            String previousContext = context(previousTranslations);
            ParagraphTranslationRequest request = request(paragraph, sourceLanguage, targetLanguage, options,
                    glossary, previousContext);
            Map<String, String> translated;
            try {
                translated = validateResponse(paragraph, paragraphGateway.translate(request), true);
            } catch (InvalidAiResponseException exception) {
                String reason = exception.reason().name();
                log.warn("Paragraph translation failed for {} at model stage (segments={}, reason=MODEL_SEGMENT_FAILED, detail={})",
                        paragraph.id(), paragraph.segments().size(), reason);
                lastFailure = exception;
                continue;
            }
            boolean complete = translated.keySet().size() == paragraph.segments().size();
            if (!complete) {
                Map<String, String> accepted = translated;
                Set<String> missing = paragraph.segments().stream().map(StyledTextSegment::id)
                        .filter(id -> !accepted.containsKey(id)).collect(Collectors.toSet());
                log.warn("Paragraph {} translated partially (successfulSegments={}, failedSegmentIds={})",
                        paragraph.id(), translated.size(), missing);
            }
            List<String> violations = complete ? consistencyViolations(paragraph, translated, glossary,
                    options.protectedTerms()) : List.of();
            if (!violations.isEmpty()) {
                String repairContext = previousContext + (previousContext.isEmpty() ? "" : "\n")
                        + "Consistency issues to fix: " + String.join("; ", violations);
                try {
                    Map<String, String> repaired = validateResponse(paragraph,
                            paragraphGateway.translate(request(paragraph, sourceLanguage, targetLanguage, options,
                                    glossary, repairContext)), true);
                    if (consistencyViolations(paragraph, repaired, glossary, options.protectedTerms()).isEmpty()) {
                        translated = repaired;
                    } else {
                        log.warn("Consistency repair did not satisfy glossary for paragraph {}; retaining initial translation",
                                paragraph.id());
                    }
                } catch (RuntimeException exception) {
                    log.warn("Consistency repair failed for paragraph {}; retaining initial translation",
                            paragraph.id(), exception);
                }
            }
            translations.put(paragraph.id(), translated);
            previousTranslations.addLast(translatedText(paragraph, translated));
            while (previousTranslations.size() > 2) {
                previousTranslations.removeFirst();
            }
        }
        if (translations.isEmpty()) {
            if (lastFailure != null) {
                throw lastFailure;
            }
            throw new InvalidAiResponseException("模型未译出任何文档段落",
                    InvalidAiResponseException.Reason.EMPTY_TRANSLATION);
        }
        return new DocumentTranslationResult(translations);
    }

    /** Word batches use source context so their model calls can run independently. */
    public DocumentTranslationResult translateWordBatched(List<TranslationParagraph> paragraphs,
                                                            Language sourceLanguage,
                                                            Language targetLanguage,
                                                            DocumentTranslationOptions options) {
        if (paragraphs == null || paragraphs.isEmpty()) {
            throw new InvalidTranslationRequestException("文档中没有可翻译的文本");
        }
        long started = System.nanoTime();
        ExecutorService workers = Executors.newFixedThreadPool(PDF_DOCUMENT_CONCURRENCY);
        try {
            long glossaryStarted = System.nanoTime();
            List<String> chunks = glossaryChunks(paragraphs);
            List<Future<List<GlossaryEntry>>> termJobs = chunks.stream()
                    .map(chunk -> workers.submit(() -> extractChunk(chunk, sourceLanguage, targetLanguage, options)))
                    .toList();
            List<GlossaryEntry> extracted = new ArrayList<>();
            for (Future<List<GlossaryEntry>> job : termJobs) {
                extracted.addAll(await(job));
            }
            List<GlossaryEntry> glossary = mergeGlossary(extracted, options);
            long glossaryMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - glossaryStarted);

            List<DocxBatch> batches = docxBatches(paragraphs);
            AtomicInteger modelRequests = new AtomicInteger();
            AtomicInteger splitRetries = new AtomicInteger();
            ParagraphRequestMetrics requestMetrics = new ParagraphRequestMetrics();
            List<Future<Map<String, Map<String, String>>>> jobs = batches.stream()
                    .map(batch -> workers.submit(() -> translateDocxBatch(batch, sourceLanguage, targetLanguage,
                            options, glossary, modelRequests, splitRetries, requestMetrics)))
                    .toList();
            Map<String, Map<String, String>> unordered = new LinkedHashMap<>();
            for (Future<Map<String, Map<String, String>>> job : jobs) {
                unordered.putAll(await(job));
            }
            Map<String, Map<String, String>> ordered = new LinkedHashMap<>();
            for (TranslationParagraph paragraph : paragraphs) {
                Map<String, String> values = unordered.get(paragraph.id());
                if (values != null && !values.isEmpty()) {
                    ordered.put(paragraph.id(), values);
                }
            }
            int totalSegments = paragraphs.stream().mapToInt(paragraph -> paragraph.segments().size()).sum();
            int translatedSegments = ordered.values().stream().mapToInt(Map::size).sum();
            log.info("DOCX model stage finished (paragraphs={}, batches={}, modelRequests={}, mainApiRequests={}, "
                            + "fallbackRequests={}, splitRetries={}, glossaryRequests={}, translatedSegments={}, "
                            + "retainedSegments={}, glossaryMs={}, "
                            + "modelMs={}, totalMs={})",
                    paragraphs.size(), batches.size(), modelRequests.get(), requestMetrics.mainRequests(),
                    requestMetrics.fallbackRequests(), splitRetries.get(), chunks.size(),
                    translatedSegments, totalSegments - translatedSegments, glossaryMillis,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - glossaryStarted) - glossaryMillis,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (ordered.isEmpty()) {
                throw new InvalidAiResponseException("模型未译出任何文档段落",
                        InvalidAiResponseException.Reason.EMPTY_TRANSLATION);
            }
            return new DocumentTranslationResult(ordered);
        } finally {
            workers.shutdownNow();
        }
    }

    private List<DocxBatch> docxBatches(List<TranslationParagraph> paragraphs) {
        List<DocxBatch> batches = new ArrayList<>();
        List<TranslationParagraph> current = new ArrayList<>();
        StringBuilder sourceHistory = new StringBuilder();
        String context = "";
        String part = "";
        int characters = 0;
        int segments = 0;
        for (TranslationParagraph paragraph : paragraphs) {
            String nextPart = docxPart(paragraph.id());
            int length = paragraph.sourceText().length();
            int segmentCount = paragraph.segments().size();
            if (!part.equals(nextPart)) {
                if (!current.isEmpty()) {
                    batches.add(new DocxBatch(List.copyOf(current), context));
                    current.clear();
                }
                sourceHistory.setLength(0);
                characters = 0;
                segments = 0;
            } else if (!current.isEmpty() && (current.size() >= DOCX_BATCH_PARAGRAPHS
                    || characters + length > DOCX_BATCH_CHARACTERS
                    || segments + segmentCount > DOCX_BATCH_SEGMENTS)) {
                batches.add(new DocxBatch(List.copyOf(current), context));
                current.clear();
                characters = 0;
                segments = 0;
            }
            if (current.isEmpty()) {
                int limit = Math.min(contextMaxCharacters, DOCX_SOURCE_CONTEXT_CHARACTERS);
                context = sourceHistory.substring(Math.max(0, sourceHistory.length() - limit));
                part = nextPart;
            }
            current.add(paragraph);
            characters += length;
            segments += segmentCount;
            sourceHistory.append(paragraph.sourceText()).append(' ');
        }
        if (!current.isEmpty()) {
            batches.add(new DocxBatch(List.copyOf(current), context));
        }
        return batches;
    }

    private String docxPart(String paragraphId) {
        int suffix = paragraphId.lastIndexOf("#p");
        return suffix < 0 ? paragraphId : paragraphId.substring(0, suffix);
    }

    private Map<String, Map<String, String>> translateDocxBatch(DocxBatch batch, Language source,
                                                                 Language target, DocumentTranslationOptions options,
                                                                 List<GlossaryEntry> glossary,
                                                                 AtomicInteger modelRequests,
                                                                 AtomicInteger splitRetries,
                                                                 ParagraphRequestMetrics requestMetrics) {
        List<StyledTextSegment> segments = batch.paragraphs().stream()
                .flatMap(paragraph -> paragraph.segments().stream()).toList();
        String text = batch.paragraphs().stream().map(TranslationParagraph::sourceText)
                .collect(Collectors.joining(" "));
        List<GlossaryEntry> relevant = glossary.stream()
                .filter(term -> text.contains(term.sourceTerm())).toList();
        ParagraphTranslationRequest request = new ParagraphTranslationRequest(source, target,
                options.documentType(), options.translationStyle(), relevant, options.protectedTerms(),
                batch.sourceContext(), segments);
        try {
            Map<String, String> response = callDocxModel(request, modelRequests, requestMetrics);
            Set<String> expected = segments.stream().map(StyledTextSegment::id).collect(Collectors.toSet());
            if (response == null || response.isEmpty()
                    || response.keySet().stream().anyMatch(id -> !expected.contains(id))
                    || response.values().stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new InvalidAiResponseException("Word 批次片段映射无效",
                        InvalidAiResponseException.Reason.SEGMENT_MAPPING);
            }
            Map<String, Map<String, String>> result = new LinkedHashMap<>();
            for (TranslationParagraph paragraph : batch.paragraphs()) {
                Map<String, String> values = new LinkedHashMap<>();
                for (StyledTextSegment segment : paragraph.segments()) {
                    String translated = response.get(segment.id());
                    if (translated != null) {
                        values.put(segment.id(), translated);
                    } else {
                        log.warn("DOCX segment {} retained in source language (reason=MODEL_SEGMENT_FAILED)",
                                segment.id());
                    }
                }
                if (!values.isEmpty()) {
                    result.put(paragraph.id(), values);
                }
            }
            repairDocxConsistency(batch, result, source, target, options, relevant,
                    modelRequests, requestMetrics);
            return result;
        } catch (InvalidAiResponseException exception) {
            log.warn("DOCX batch response rejected for {} (paragraphs={}, reason={})",
                    batch.paragraphs().get(0).id(), batch.paragraphs().size(), exception.reason());
            if (batch.paragraphs().size() == 1) {
                batch.paragraphs().get(0).segments().forEach(segment ->
                        log.warn("DOCX segment {} retained in source language (reason=MODEL_SEGMENT_FAILED)",
                                segment.id()));
                return Map.of();
            }
            splitRetries.incrementAndGet();
            int middle = batch.paragraphs().size() / 2;
            Map<String, Map<String, String>> result = new LinkedHashMap<>();
            result.putAll(translateDocxBatch(new DocxBatch(batch.paragraphs().subList(0, middle),
                    batch.sourceContext()), source, target, options, glossary, modelRequests, splitRetries,
                    requestMetrics));
            result.putAll(translateDocxBatch(new DocxBatch(batch.paragraphs().subList(middle,
                    batch.paragraphs().size()), batch.sourceContext()), source, target, options, glossary,
                    modelRequests, splitRetries, requestMetrics));
            return result;
        }
    }

    private void repairDocxConsistency(DocxBatch batch, Map<String, Map<String, String>> result,
                                        Language source, Language target, DocumentTranslationOptions options,
                                        List<GlossaryEntry> relevant, AtomicInteger modelRequests,
                                        ParagraphRequestMetrics requestMetrics) {
        List<TranslationParagraph> affected = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        for (TranslationParagraph paragraph : batch.paragraphs()) {
            Map<String, String> translated = result.get(paragraph.id());
            if (translated == null || translated.size() != paragraph.segments().size()) {
                continue;
            }
            List<String> violations = consistencyViolations(paragraph, translated, relevant,
                    options.protectedTerms());
            if (!violations.isEmpty()) {
                affected.add(paragraph);
                issues.add(paragraph.id() + ": " + String.join("; ", violations));
            }
        }
        if (affected.isEmpty()) {
            return;
        }
        List<StyledTextSegment> segments = affected.stream()
                .flatMap(paragraph -> paragraph.segments().stream()).toList();
        String context = batch.sourceContext() + "\nConsistency issues to fix: " + String.join("; ", issues);
        ParagraphTranslationRequest repair = new ParagraphTranslationRequest(source, target,
                options.documentType(), options.translationStyle(), relevant, options.protectedTerms(),
                context, segments);
        try {
            Map<String, String> response = callDocxModel(repair, modelRequests, requestMetrics);
            for (TranslationParagraph paragraph : affected) {
                Map<String, String> paragraphResponse = new LinkedHashMap<>();
                for (StyledTextSegment segment : paragraph.segments()) {
                    if (response != null && response.containsKey(segment.id())) {
                        paragraphResponse.put(segment.id(), response.get(segment.id()));
                    }
                }
                Map<String, String> repaired = validateResponse(paragraph, paragraphResponse, false);
                if (consistencyViolations(paragraph, repaired, relevant, options.protectedTerms()).isEmpty()) {
                    result.put(paragraph.id(), repaired);
                } else {
                    log.warn("DOCX consistency repair did not satisfy glossary for {}; retaining initial translation",
                            paragraph.id());
                }
            }
        } catch (RuntimeException exception) {
            log.warn("DOCX consistency repair failed for {}; retaining initial translations",
                    batch.paragraphs().get(0).id(), exception);
        }
    }

    private Map<String, String> callDocxModel(ParagraphTranslationRequest request, AtomicInteger modelRequests,
                                               ParagraphRequestMetrics requestMetrics) {
        try {
            DOCUMENT_GLOBAL_MODEL_SLOTS.acquire();
            try {
                modelRequests.incrementAndGet();
                return paragraphGateway.translate(request, requestMetrics);
            } finally {
                DOCUMENT_GLOBAL_MODEL_SLOTS.release();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Word 翻译被中断", exception);
        }
    }

    private record DocxBatch(List<TranslationParagraph> paragraphs, String sourceContext) {
    }

    /** PDF requests can be independent because each batch carries source, rather than translated, context. */
    public DocumentTranslationResult translatePdfBatched(List<TranslationParagraph> paragraphs,
                                                          Language sourceLanguage,
                                                          Language targetLanguage,
                                                          DocumentTranslationOptions options) {
        if (paragraphs == null || paragraphs.isEmpty()) {
            throw new InvalidTranslationRequestException("PDF 中没有可翻译的正文");
        }
        long started = System.nanoTime();
        ExecutorService workers = Executors.newFixedThreadPool(PDF_DOCUMENT_CONCURRENCY);
        try {
            long glossaryStarted = System.nanoTime();
            List<String> chunks = glossaryChunks(paragraphs);
            List<Future<List<GlossaryEntry>>> termJobs = chunks.stream()
                    .map(chunk -> workers.submit(() -> extractChunk(chunk, sourceLanguage, targetLanguage, options)))
                    .toList();
            List<GlossaryEntry> terms = new ArrayList<>();
            for (Future<List<GlossaryEntry>> job : termJobs) {
                terms.addAll(await(job));
            }
            List<GlossaryEntry> glossary = mergeGlossary(terms, options);
            long glossaryMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - glossaryStarted);

            PdfPrepared prepared = preparePdfRequests(paragraphs);
            List<PdfBatch> batches = pdfBatches(prepared.requests());
            AtomicInteger modelRequests = new AtomicInteger();
            AtomicInteger splitRetries = new AtomicInteger();
            List<Future<Map<String, Map<String, String>>>> jobs = batches.stream()
                    .map(batch -> workers.submit(() -> translatePdfBatch(batch, sourceLanguage, targetLanguage,
                            options, glossary, true, modelRequests, splitRetries)))
                    .toList();
            Map<String, Map<String, String>> requestTranslations = new LinkedHashMap<>();
            for (Future<Map<String, Map<String, String>>> job : jobs) {
                requestTranslations.putAll(await(job));
            }
            Map<String, Map<String, String>> translated = joinPdfRequestParts(
                    paragraphs, prepared.parts(), requestTranslations);
            log.info("PDF model stage finished (blocks={}, batches={}, modelRequests={}, splitRetries={}, "
                            + "glossaryRequests={}, translated={}, retained={}, glossaryMs={}, modelMs={}, totalMs={})",
                    paragraphs.size(), batches.size(), modelRequests.get(), splitRetries.get(), chunks.size(),
                    translated.size(), paragraphs.size() - translated.size(), glossaryMillis,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - glossaryStarted) - glossaryMillis,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (translated.isEmpty()) {
                throw new InvalidAiResponseException("模型未译出任何 PDF 正文");
            }
            return new DocumentTranslationResult(translated);
        } finally {
            workers.shutdownNow();
        }
    }

    private PdfPrepared preparePdfRequests(List<TranslationParagraph> paragraphs) {
        List<TranslationParagraph> requests = new ArrayList<>();
        Map<String, List<PdfPart>> parts = new LinkedHashMap<>();
        for (TranslationParagraph paragraph : paragraphs) {
            if (paragraph.sourceText().length() <= PDF_BATCH_CHARACTERS) {
                requests.add(paragraph);
                parts.put(paragraph.id(), paragraph.segments().stream()
                        .map(segment -> new PdfPart(segment.id(), paragraph.id(), segment.id()))
                        .toList());
                continue;
            }
            List<TranslationParagraph> paragraphRequests = new ArrayList<>();
            List<PdfPart> paragraphParts = new ArrayList<>();
            boolean unsplittable = false;
            int number = 0;
            for (StyledTextSegment segment : paragraph.segments()) {
                List<String> chunks = pdfSentenceChunks(segment.sourceText());
                if (chunks.isEmpty()) {
                    unsplittable = true;
                    break;
                }
                for (String chunk : chunks) {
                    String requestId = paragraph.id() + "@part-" + number;
                    String segmentId = segment.id() + "@part-" + number++;
                    paragraphRequests.add(new TranslationParagraph(requestId,
                            List.of(new StyledTextSegment(segmentId, chunk, segment.styleSignature()))));
                    paragraphParts.add(new PdfPart(segment.id(), requestId, segmentId));
                }
            }
            if (unsplittable) {
                log.warn("PDF block {} retained in source language (reason=OVERSIZE_SENTENCE, chars={})",
                        paragraph.id(), paragraph.sourceText().length());
                continue;
            }
            requests.addAll(paragraphRequests);
            parts.put(paragraph.id(), List.copyOf(paragraphParts));
        }
        return new PdfPrepared(List.copyOf(requests), parts);
    }

    private List<String> pdfSentenceChunks(String text) {
        if (text.length() <= PDF_BATCH_CHARACTERS) {
            return List.of(text);
        }
        List<String> sentences = new ArrayList<>();
        Matcher boundary = PDF_SENTENCE_END.matcher(text);
        int start = 0;
        while (boundary.find()) {
            int end = boundary.end();
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            sentences.add(text.substring(start, end));
            start = end;
        }
        if (start < text.length()) {
            sentences.add(text.substring(start));
        }
        if (sentences.stream().anyMatch(sentence -> sentence.length() > PDF_BATCH_CHARACTERS)) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (!current.isEmpty() && current.length() + sentence.length() > PDF_BATCH_CHARACTERS) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private Map<String, Map<String, String>> joinPdfRequestParts(
            List<TranslationParagraph> originals, Map<String, List<PdfPart>> parts,
            Map<String, Map<String, String>> requests) {
        Map<String, Map<String, String>> joined = new LinkedHashMap<>();
        for (TranslationParagraph original : originals) {
            List<PdfPart> paragraphParts = parts.get(original.id());
            if (paragraphParts == null) {
                continue;
            }
            Map<String, String> segmentTranslations = new LinkedHashMap<>();
            boolean complete = true;
            for (StyledTextSegment segment : original.segments()) {
                List<String> translatedParts = new ArrayList<>();
                for (PdfPart part : paragraphParts) {
                    if (!part.originalSegmentId().equals(segment.id())) {
                        continue;
                    }
                    String translation = requests.getOrDefault(part.requestParagraphId(), Map.of())
                            .get(part.requestSegmentId());
                    if (translation == null || translation.isBlank()) {
                        complete = false;
                        break;
                    }
                    translatedParts.add(translation);
                }
                if (!complete || translatedParts.isEmpty()) {
                    complete = false;
                    break;
                }
                segmentTranslations.put(segment.id(), String.join(" ", translatedParts));
            }
            if (complete) {
                joined.put(original.id(), segmentTranslations);
            }
        }
        return joined;
    }

    private record PdfPrepared(List<TranslationParagraph> requests, Map<String, List<PdfPart>> parts) {
    }

    private record PdfPart(String originalSegmentId, String requestParagraphId, String requestSegmentId) {
    }

    private List<PdfBatch> pdfBatches(List<TranslationParagraph> paragraphs) {
        List<PdfBatch> result = new ArrayList<>();
        List<TranslationParagraph> current = new ArrayList<>();
        StringBuilder sourceHistory = new StringBuilder();
        String context = "";
        String page = "";
        int characters = 0;
        for (TranslationParagraph paragraph : paragraphs) {
            String nextPage = paragraph.id().split("#", 2)[0];
            int length = paragraph.sourceText().length();
            if (!current.isEmpty() && (!page.equals(nextPage) || current.size() >= PDF_BATCH_BLOCKS
                    || characters + length > PDF_BATCH_CHARACTERS)) {
                result.add(new PdfBatch(List.copyOf(current), context));
                current.clear();
                characters = 0;
            }
            if (current.isEmpty()) {
                context = sourceHistory.substring(Math.max(0, sourceHistory.length() - 400));
                page = nextPage;
            }
            current.add(paragraph);
            characters += length;
            sourceHistory.append(paragraph.sourceText()).append(' ');
        }
        if (!current.isEmpty()) {
            result.add(new PdfBatch(List.copyOf(current), context));
        }
        return result;
    }

    private Map<String, Map<String, String>> translatePdfBatch(PdfBatch batch, Language source,
                                                               Language target, DocumentTranslationOptions options,
                                                               List<GlossaryEntry> glossary, boolean maySplit,
                                                               AtomicInteger modelRequests, AtomicInteger splitRetries) {
        List<StyledTextSegment> segments = batch.paragraphs().stream()
                .flatMap(paragraph -> paragraph.segments().stream()).toList();
        String text = batch.paragraphs().stream().map(TranslationParagraph::sourceText)
                .collect(Collectors.joining(" "));
        List<GlossaryEntry> relevant = glossary.stream()
                .filter(term -> text.contains(term.sourceTerm())).toList();
        ParagraphTranslationRequest request = new ParagraphTranslationRequest(source, target,
                options.documentType(), options.translationStyle(), relevant, options.protectedTerms(),
                batch.sourceContext(), segments);
        try {
            Map<String, String> response = callPdfModel(request, modelRequests);
            Set<String> expected = segments.stream().map(StyledTextSegment::id).collect(Collectors.toSet());
            if (response == null || !response.keySet().equals(expected)
                    || response.values().stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new InvalidAiResponseException("PDF 批次片段映射无效");
            }
            Map<String, Map<String, String>> result = new LinkedHashMap<>();
            for (TranslationParagraph paragraph : batch.paragraphs()) {
                Map<String, String> paragraphResult = new LinkedHashMap<>();
                paragraph.segments().forEach(segment -> paragraphResult.put(segment.id(), response.get(segment.id())));
                result.put(paragraph.id(), paragraphResult);
            }
            return result;
        } catch (RuntimeException exception) {
            if (exception instanceof NonTransientAiException) {
                throw exception;
            }
            String reference = batch.paragraphs().get(0).id();
            log.warn("PDF batch translation failed for {} (blocks={}, reason={})", reference,
                    batch.paragraphs().size(), exception instanceof InvalidAiResponseException invalid
                            ? invalid.reason() : exception.getClass().getSimpleName());
            if (maySplit && batch.paragraphs().size() > 2) {
                splitRetries.incrementAndGet();
                int middle = batch.paragraphs().size() / 2;
                Map<String, Map<String, String>> result = new LinkedHashMap<>();
                result.putAll(translatePdfBatch(new PdfBatch(batch.paragraphs().subList(0, middle),
                        batch.sourceContext()), source, target, options, glossary, false,
                        modelRequests, splitRetries));
                result.putAll(translatePdfBatch(new PdfBatch(batch.paragraphs().subList(middle,
                        batch.paragraphs().size()), batch.sourceContext()), source, target, options, glossary, false,
                        modelRequests, splitRetries));
                return result;
            }
            batch.paragraphs().forEach(paragraph -> log.warn("PDF block retained in source language for {} "
                    + "(reason={})", paragraph.id(), exception.getClass().getSimpleName()));
            return Map.of();
        }
    }

    private Map<String, String> callPdfModel(ParagraphTranslationRequest request, AtomicInteger modelRequests) {
        try {
            DOCUMENT_GLOBAL_MODEL_SLOTS.acquire();
            try {
                modelRequests.incrementAndGet();
                return paragraphGateway.translate(request);
            } finally {
                DOCUMENT_GLOBAL_MODEL_SLOTS.release();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDF 翻译被中断", exception);
        }
    }

    private <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDF 翻译被中断", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("PDF 翻译任务失败", exception.getCause());
        }
    }

    private record PdfBatch(List<TranslationParagraph> paragraphs, String sourceContext) {
    }

    private List<GlossaryEntry> extractGlossary(List<TranslationParagraph> paragraphs,
                                                Language sourceLanguage,
                                                Language targetLanguage,
                                                DocumentTranslationOptions options) {
        List<GlossaryEntry> extracted = new ArrayList<>();
        for (String chunk : glossaryChunks(paragraphs)) {
            extracted.addAll(extractChunk(chunk, sourceLanguage, targetLanguage, options));
        }
        return mergeGlossary(extracted, options);
    }

    private List<String> glossaryChunks(List<TranslationParagraph> paragraphs) {
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (TranslationParagraph paragraph : paragraphs) {
            String text = paragraph.sourceText();
            if (text.isBlank()) {
                continue;
            }
            int offset = 0;
            while (offset < text.length()) {
                int end = Math.min(text.length(), offset + glossaryChunkSize);
                if (chunk.length() > 0 && chunk.length() + end - offset > glossaryChunkSize) {
                    chunks.add(chunk.toString());
                    chunk.setLength(0);
                }
                chunk.append(text, offset, end);
                offset = end;
                if (chunk.length() >= glossaryChunkSize) {
                    chunks.add(chunk.toString());
                    chunk.setLength(0);
                }
            }
        }
        if (chunk.length() > 0) {
            chunks.add(chunk.toString());
        }
        return chunks;
    }

    private List<GlossaryEntry> mergeGlossary(List<GlossaryEntry> extracted, DocumentTranslationOptions options) {
        LinkedHashMap<String, GlossaryEntry> merged = new LinkedHashMap<>();
        for (GlossaryEntry entry : extracted) {
            if (entry != null && entry.sourceTerm() != null && !entry.sourceTerm().isBlank()
                    && entry.targetTerm() != null && !entry.targetTerm().isBlank()) {
                merged.putIfAbsent(entry.sourceTerm().strip(), entry);
            }
        }
        for (String protectedTerm : options.protectedTerms()) {
            merged.put(protectedTerm, new GlossaryEntry(protectedTerm, protectedTerm, "protected"));
        }
        return List.copyOf(merged.values());
    }

    private List<GlossaryEntry> extractChunk(String text,
                                             Language sourceLanguage,
                                             Language targetLanguage,
                                             DocumentTranslationOptions options) {
        try {
            List<GlossaryEntry> result = terminologyGateway.extract(new TerminologyRequest(sourceLanguage, targetLanguage,
                    options.documentType(), options.translationStyle(), options.protectedTerms(), text));
            return result == null ? List.of() : result;
        } catch (TransientAiException | NonTransientAiException | InvalidAiResponseException
                 | ResourceAccessException exception) {
            log.warn("Terminology extraction failed; continuing with protected terms only", exception);
            return List.of();
        }
    }

    private ParagraphTranslationRequest request(TranslationParagraph paragraph,
                                                Language sourceLanguage,
                                                Language targetLanguage,
                                                DocumentTranslationOptions options,
                                                List<GlossaryEntry> glossary,
                                                String previousContext) {
        return new ParagraphTranslationRequest(sourceLanguage, targetLanguage, options.documentType(),
                options.translationStyle(), glossary, options.protectedTerms(), previousContext, paragraph.segments());
    }

    private Map<String, String> validateResponse(TranslationParagraph paragraph, Map<String, String> response,
                                                 boolean allowPartial) {
        if (response == null) {
            log.warn("Model returned no paragraph translation for paragraph {}", paragraph.id());
            throw new InvalidAiResponseException("模型未返回段落翻译");
        }
        Set<String> expected = paragraph.segments().stream().map(StyledTextSegment::id).collect(Collectors.toSet());
        if ((!allowPartial && !response.keySet().equals(expected))
                || response.isEmpty()
                || response.keySet().stream().anyMatch(id -> !expected.contains(id))
                || response.values().stream().anyMatch(value -> value == null || value.isBlank())) {
            log.warn("Invalid model segment mapping for paragraph {}: expected {} segments, received {}",
                    paragraph.id(), expected.size(), response.size());
            throw new InvalidAiResponseException("模型段落翻译片段映射无效");
        }
        return Map.copyOf(response);
    }

    private List<String> consistencyViolations(TranslationParagraph paragraph,
                                                Map<String, String> translated,
                                                List<GlossaryEntry> glossary,
                                                List<String> protectedTerms) {
        String sourceText = paragraph.sourceText();
        String targetText = translatedText(paragraph, translated);
        List<String> violations = new ArrayList<>();
        for (GlossaryEntry entry : glossary) {
            if (sourceText.contains(entry.sourceTerm()) && !targetText.contains(entry.targetTerm())) {
                violations.add(entry.sourceTerm() + " 应译为 " + entry.targetTerm());
            }
        }
        for (String term : protectedTerms) {
            if (sourceText.contains(term) && !targetText.contains(term)) {
                violations.add("保护词必须保留: " + term);
            }
        }
        return violations;
    }

    private String translatedText(TranslationParagraph paragraph, Map<String, String> translated) {
        return paragraph.segments().stream()
                .map(StyledTextSegment::id)
                .map(id -> translated.getOrDefault(id, ""))
                .collect(Collectors.joining());
    }

    private String context(Deque<String> previousTranslations) {
        if (contextMaxCharacters == 0 || previousTranslations.isEmpty()) {
            return "";
        }
        String value = String.join("\n", previousTranslations);
        return value.length() <= contextMaxCharacters
                ? value
                : value.substring(value.length() - contextMaxCharacters);
    }
}
