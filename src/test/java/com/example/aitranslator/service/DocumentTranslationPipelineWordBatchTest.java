package com.example.aitranslator.service;

import com.example.aitranslator.ai.ParagraphTranslationGateway;
import com.example.aitranslator.ai.StyledTextSegment;
import com.example.aitranslator.exception.InvalidAiResponseException;
import com.example.aitranslator.domain.Language;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTranslationPipelineWordBatchTest {

    private static final DocumentTranslationOptions OPTIONS =
            new DocumentTranslationOptions(null, null, List.of());

    @Test
    void batchesTemplateSizedWordDocumentWithoutCrossingPartOrRequestLimits() {
        List<ParagraphTranslationRequestSnapshot> requests = new CopyOnWriteArrayList<>();
        ParagraphTranslationGateway model = request -> {
            requests.add(new ParagraphTranslationRequestSnapshot(request));
            return request.currentSegments().stream().collect(java.util.stream.Collectors.toMap(
                    StyledTextSegment::id, segment -> "译:" + segment.sourceText()));
        };

        DocumentTranslationResult result = new DocumentTranslationPipeline(
                request -> List.of(), model, 12_000, 2_000)
                .translateWordBatched(templateSizedParagraphs(129), Language.EN, Language.ZH_CN, OPTIONS);

        assertThat(requests).hasSize(33);
        ParagraphTranslationRequestSnapshot secondBatch = requests.stream()
                .filter(request -> request.segments().get(0).id().startsWith("word/document.xml#p4#"))
                .findFirst().orElseThrow();
        assertThat(secondBatch.previousContext()).contains("source 0");
        assertThat(secondBatch.previousContext()).doesNotContain("译:");
        assertThat(result.translations()).hasSize(129);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.segments()).hasSizeLessThanOrEqualTo(16);
            assertThat(request.sourceCharacters()).isLessThanOrEqualTo(2_400);
            assertThat(request.segmentPartNames()).containsOnly("word/document.xml");
        });
    }

    @Test
    void allowsAtMostTwoConcurrentWordModelRequestsAndReturnsDocumentOrder() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicInteger glossaryActive = new AtomicInteger();
        AtomicInteger maximumGlossaryActive = new AtomicInteger();
        List<String> seen = new CopyOnWriteArrayList<>();
        var terminology = (com.example.aitranslator.ai.TerminologyModelGateway) request -> {
            int now = glossaryActive.incrementAndGet();
            maximumGlossaryActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                glossaryActive.decrementAndGet();
            }
            return List.of();
        };
        ParagraphTranslationGateway model = request -> {
            seen.add(request.currentSegments().get(0).id());
            int now = active.incrementAndGet();
            maximumActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                active.decrementAndGet();
            }
            return request.currentSegments().stream().collect(java.util.stream.Collectors.toMap(
                    StyledTextSegment::id, segment -> "译文"));
        };

        DocumentTranslationResult result = new DocumentTranslationPipeline(
                terminology, model, 10, 2_000)
                .translateWordBatched(templateSizedParagraphs(12), Language.EN, Language.ZH_CN, OPTIONS);

        assertThat(maximumActive).hasValue(2);
        assertThat(maximumGlossaryActive.get()).isLessThanOrEqualTo(2);
        assertThat(result.translations().keySet()).containsExactlyElementsOf(
                templateSizedParagraphs(12).stream().map(TranslationParagraph::id).toList());
    }

    @Test
    void splitsAnInvalidWordBatchUntilSuccessfulSubBatchesAreTranslated() {
        AtomicInteger calls = new AtomicInteger();
        ParagraphTranslationGateway model = request -> {
            calls.incrementAndGet();
            if (request.currentSegments().size() > 2) {
                throw new InvalidAiResponseException("invalid batch", InvalidAiResponseException.Reason.EMPTY_TRANSLATION);
            }
            return request.currentSegments().stream().collect(java.util.stream.Collectors.toMap(
                    StyledTextSegment::id, segment -> "译文"));
        };

        DocumentTranslationResult result = new DocumentTranslationPipeline(
                request -> List.of(), model, 12_000, 2_000)
                .translateWordBatched(paragraphs(4), Language.EN, Language.ZH_CN, OPTIONS);

        assertThat(calls).hasValue(3);
        assertThat(result.translations()).hasSize(4);
    }

    @Test
    void retainsSuccessfulSegmentsWhenWordBatchMappingIsPartial() {
        ParagraphTranslationGateway model = request -> {
            Map<String, String> response = new LinkedHashMap<>();
            request.currentSegments().stream().limit(2)
                    .forEach(segment -> response.put(segment.id(), "译文"));
            return response;
        };

        DocumentTranslationResult result = new DocumentTranslationPipeline(
                request -> List.of(), model, 12_000, 2_000)
                .translateWordBatched(paragraphsWithSegments(4, 3), Language.EN, Language.ZH_CN, OPTIONS);

        assertThat(result.translations()).isNotEmpty();
        assertThat(result.translations().values().stream().mapToInt(Map::size).sum()).isEqualTo(2);
    }

    @Test
    void doesNotBatchParagraphsAcrossDifferentDocxParts() {
        List<ParagraphTranslationRequestSnapshot> requests = new CopyOnWriteArrayList<>();
        ParagraphTranslationGateway model = request -> {
            requests.add(new ParagraphTranslationRequestSnapshot(request));
            return request.currentSegments().stream().collect(java.util.stream.Collectors.toMap(
                    StyledTextSegment::id, segment -> "译文"));
        };

        new DocumentTranslationPipeline(request -> List.of(), model, 12_000, 2_000)
                .translateWordBatched(List.of(
                        paragraph("word/document.xml", 0), paragraph("word/header1.xml", 0)),
                        Language.EN, Language.ZH_CN, OPTIONS);

        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request -> assertThat(request.segmentPartNames()).hasSize(1));
    }

    @Test
    void throwsInvalidAiResponseWhenEveryWordBatchFails() {
        ParagraphTranslationGateway model = request -> {
            throw new InvalidAiResponseException("invalid batch", InvalidAiResponseException.Reason.EMPTY_TRANSLATION);
        };

        assertThatThrownBy(() -> new DocumentTranslationPipeline(
                request -> List.of(), model, 12_000, 2_000)
                .translateWordBatched(paragraphs(4), Language.EN, Language.ZH_CN, OPTIONS))
                .isInstanceOf(InvalidAiResponseException.class);
    }

    private List<TranslationParagraph> templateSizedParagraphs(int count) {
        List<TranslationParagraph> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(paragraph("word/document.xml", index));
        }
        return result;
    }

    private List<TranslationParagraph> paragraphs(int count) {
        return templateSizedParagraphs(count);
    }

    private List<TranslationParagraph> paragraphsWithSegments(int count, int segments) {
        List<TranslationParagraph> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<StyledTextSegment> current = new ArrayList<>();
            for (int segment = 0; segment < segments; segment++) {
                current.add(new StyledTextSegment("word/document.xml#p" + index + "#s" + segment,
                        "source " + index + ":" + segment, "NORMAL"));
            }
            result.add(new TranslationParagraph("word/document.xml#p" + index, current));
        }
        return result;
    }

    private TranslationParagraph paragraph(String part, int index) {
        return new TranslationParagraph(part + "#p" + index,
                List.of(new StyledTextSegment(part + "#p" + index + "#s0", "source " + index, "NORMAL")));
    }

    private record ParagraphTranslationRequestSnapshot(
            List<StyledTextSegment> segments, int sourceCharacters, List<String> segmentPartNames,
            String previousContext) {
        ParagraphTranslationRequestSnapshot(com.example.aitranslator.ai.ParagraphTranslationRequest request) {
            this(request.currentSegments(), request.currentSegments().stream()
                    .mapToInt(segment -> segment.sourceText().length()).sum(), request.currentSegments().stream()
                    .map(segment -> segment.id().split("#p", 2)[0]).distinct().toList(), request.previousContext());
        }
    }
}
