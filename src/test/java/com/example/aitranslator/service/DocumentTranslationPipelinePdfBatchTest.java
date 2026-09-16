package com.example.aitranslator.service;

import com.example.aitranslator.exception.InvalidAiResponseException;
import com.example.aitranslator.ai.ParagraphTranslationGateway;
import com.example.aitranslator.ai.StyledTextSegment;
import com.example.aitranslator.domain.Language;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTranslationPipelinePdfBatchTest {

    @Test
    void batchesEightPdfBlocksIntoTwoConcurrentModelCalls() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        List<Integer> sizes = java.util.Collections.synchronizedList(new ArrayList<>());
        ParagraphTranslationGateway model = request -> {
            sizes.add(request.currentSegments().size());
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
                    StyledTextSegment::id, segment -> "译:" + segment.sourceText()));
        };
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(
                request -> List.of(), model, 12_000, 2_000);

        DocumentTranslationResult result = pipeline.translatePdfBatched(
                paragraphs(8), Language.EN, Language.ZH_CN,
                new DocumentTranslationOptions(null, null, List.of()));

        assertThat(sizes).containsExactlyInAnyOrder(4, 4);
        assertThat(maximumActive.get()).isEqualTo(2);
        assertThat(result.translations()).hasSize(8);
        assertThat(result.translations().get("pdf-page-1#block-7").get("s7")).isEqualTo("译:source 7");
    }

    @Test
    void splitsInvalidFourBlockBatchAndRetainsOnlyFailedSubbatch() {
        AtomicInteger calls = new AtomicInteger();
        ParagraphTranslationGateway model = request -> {
            calls.incrementAndGet();
            if (request.currentSegments().size() > 2 || request.currentSegments().get(0).id().equals("s2")) {
                throw new InvalidAiResponseException("blank JSON", InvalidAiResponseException.Reason.EMPTY_TRANSLATION);
            }
            return request.currentSegments().stream().collect(java.util.stream.Collectors.toMap(
                    StyledTextSegment::id, segment -> "译文"));
        };
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(
                request -> List.of(), model, 12_000, 2_000);

        DocumentTranslationResult result = pipeline.translatePdfBatched(
                paragraphs(4), Language.EN, Language.ZH_CN,
                new DocumentTranslationOptions(null, null, List.of()));

        assertThat(calls.get()).isEqualTo(3);
        assertThat(result.translations()).containsKeys("pdf-page-1#block-0", "pdf-page-1#block-1")
                .doesNotContainKeys("pdf-page-1#block-2", "pdf-page-1#block-3");
    }

    @Test
    void splitsOversizedParagraphAtSentenceBoundariesAndRejoinsItsRegion() {
        List<Integer> requestCharacters = java.util.Collections.synchronizedList(new ArrayList<>());
        ParagraphTranslationGateway model = request -> {
            requestCharacters.add(request.currentSegments().stream()
                    .mapToInt(segment -> segment.sourceText().length()).sum());
            return request.currentSegments().stream().collect(java.util.stream.Collectors.toMap(
                    StyledTextSegment::id, segment -> "译:" + segment.sourceText()));
        };
        String source = "A full sentence about translation quality. ".repeat(70);
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(
                request -> List.of(), model, 12_000, 2_000);

        DocumentTranslationResult result = pipeline.translatePdfBatched(
                List.of(new TranslationParagraph("pdf-page-1#block-0",
                        List.of(new StyledTextSegment("source-0", source, "PARAGRAPH")))),
                Language.EN, Language.ZH_CN, new DocumentTranslationOptions(null, null, List.of()));

        assertThat(requestCharacters).allMatch(length -> length <= 2_400);
        assertThat(requestCharacters).hasSizeGreaterThan(1);
        assertThat(result.translations().get("pdf-page-1#block-0").get("source-0"))
                .contains("译:A full sentence about translation quality.");
    }

    @Test
    void retainsUnsplittableOversizedSentenceWithoutSendingItToModel() {
        List<Integer> requestCharacters = new ArrayList<>();
        ParagraphTranslationGateway model = request -> {
            requestCharacters.add(request.currentSegments().stream()
                    .mapToInt(segment -> segment.sourceText().length()).sum());
            return request.currentSegments().stream().collect(java.util.stream.Collectors.toMap(
                    StyledTextSegment::id, segment -> "中文译文"));
        };
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(
                request -> List.of(), model, 12_000, 2_000);
        List<TranslationParagraph> inputs = List.of(
                new TranslationParagraph("pdf-page-1#block-0",
                        List.of(new StyledTextSegment("s0", "Safe sentence.", "PARAGRAPH"))),
                new TranslationParagraph("pdf-page-1#block-1",
                        List.of(new StyledTextSegment("s1", "a".repeat(2_401), "PARAGRAPH"))));

        DocumentTranslationResult result = pipeline.translatePdfBatched(inputs,
                Language.EN, Language.ZH_CN, new DocumentTranslationOptions(null, null, List.of()));

        assertThat(requestCharacters).containsExactly(14);
        assertThat(result.translations()).containsKey("pdf-page-1#block-0")
                .doesNotContainKey("pdf-page-1#block-1");
    }

    private List<TranslationParagraph> paragraphs(int count) {
        List<TranslationParagraph> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(new TranslationParagraph("pdf-page-1#block-" + index,
                    List.of(new StyledTextSegment("s" + index, "source " + index, "PARAGRAPH"))));
        }
        return result;
    }
}
