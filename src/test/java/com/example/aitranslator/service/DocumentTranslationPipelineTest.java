package com.example.aitranslator.service;

import com.example.aitranslator.exception.InvalidTranslationRequestException;
import com.example.aitranslator.ai.GlossaryEntry;
import com.example.aitranslator.exception.InvalidAiResponseException;
import com.example.aitranslator.ai.ParagraphTranslationGateway;
import com.example.aitranslator.ai.ParagraphTranslationRequest;
import com.example.aitranslator.ai.StyledTextSegment;
import com.example.aitranslator.ai.TerminologyModelGateway;
import com.example.aitranslator.ai.TerminologyRequest;
import com.example.aitranslator.domain.DocumentType;
import com.example.aitranslator.domain.Language;
import com.example.aitranslator.domain.TranslationStyle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTranslationPipelineTest {

    @Test
    void extractsGlossaryOnceAndCarriesItAndPreviousContextToEveryParagraph() {
        List<ParagraphTranslationRequest> requests = new ArrayList<>();
        TerminologyModelGateway terminology = request -> List.of(new GlossaryEntry("API", "接口", "term"));
        ParagraphTranslationGateway translator = request -> {
            requests.add(request);
            return Map.of(request.currentSegments().get(0).id(), "译文 API");
        };
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(terminology, translator, 10, 2_000);
        List<TranslationParagraph> paragraphs = List.of(
                paragraph("p1", "第一段"), paragraph("p2", "第二段"));

        DocumentTranslationResult result = pipeline.translate(paragraphs, Language.EN, Language.ZH_CN,
                new DocumentTranslationOptions(DocumentType.TECHNICAL, TranslationStyle.FORMAL, List.of("SDK")));

        assertThat(result.translations()).hasSize(2);
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.documentType()).isEqualTo(DocumentType.TECHNICAL);
            assertThat(request.translationStyle()).isEqualTo(TranslationStyle.FORMAL);
            assertThat(request.glossary()).contains(new GlossaryEntry("API", "接口", "term"));
            assertThat(request.protectedTerms()).containsExactly("SDK");
        });
        assertThat(requests.get(0).previousContext()).isEmpty();
        assertThat(requests.get(1).previousContext()).contains("译文 API");
    }

    @Test
    void repairsParagraphWhenGlossaryTermIsMissing() {
        int[] calls = {0};
        TerminologyModelGateway terminology = request -> List.of(new GlossaryEntry("API", "接口", "term"));
        ParagraphTranslationGateway translator = request -> {
            calls[0]++;
            return Map.of(request.currentSegments().get(0).id(), calls[0] == 1 ? "首轮译文" : "包含接口");
        };
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(terminology, translator, 100, 2_000);

        DocumentTranslationResult result = pipeline.translate(
                List.of(paragraph("p1", "API")), Language.EN, Language.ZH_CN,
                new DocumentTranslationOptions(DocumentType.GENERAL, TranslationStyle.NATURAL, List.of()));

        assertThat(calls[0]).isEqualTo(2);
        assertThat(result.translations().get("p1").get("s0")).isEqualTo("包含接口");
    }

    @Test
    void keepsInitialTranslationWhenConsistencyRepairFails() {
        int[] calls = {0};
        TerminologyModelGateway terminology = request -> List.of(new GlossaryEntry("API", "接口", "term"));
        ParagraphTranslationGateway translator = request -> {
            calls[0]++;
            if (calls[0] == 1) {
                return Map.of(request.currentSegments().get(0).id(), "首轮译文");
            }
            throw new IllegalStateException("repair unavailable");
        };
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(terminology, translator, 100, 2_000);

        DocumentTranslationResult result = pipeline.translate(
                List.of(paragraph("p1", "API")), Language.EN, Language.ZH_CN,
                new DocumentTranslationOptions(DocumentType.GENERAL, TranslationStyle.NATURAL, List.of()));

        assertThat(calls[0]).isEqualTo(2);
        assertThat(result.translations().get("p1").get("s0")).isEqualTo("首轮译文");
    }

    @Test
    void continuesWithProtectedTermsWhenTerminologyExtractionReturnsNull() {
        TerminologyModelGateway terminology = request -> null;
        ParagraphTranslationGateway translator = request -> Map.of(
                request.currentSegments().get(0).id(), request.protectedTerms().contains("SDK") ? "保留 SDK" : "译文");
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(terminology, translator, 100, 2_000);

        DocumentTranslationResult result = pipeline.translate(
                List.of(paragraph("p1", "SDK API")), Language.EN, Language.ZH_CN,
                new DocumentTranslationOptions(DocumentType.TECHNICAL, TranslationStyle.NATURAL, List.of("SDK")));

        assertThat(result.translations().get("p1").get("s0")).isEqualTo("保留 SDK");
    }

    @Test
    void treatsUnknownModelSegmentAsUpstreamResponseError() {
        TerminologyModelGateway terminology = request -> List.of();
        ParagraphTranslationGateway translator = request -> Map.of("unexpected", "译文");
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(terminology, translator, 100, 2_000);

        assertThatThrownBy(() -> pipeline.translate(
                List.of(paragraph("p1", "source")), Language.EN, Language.ZH_CN,
                new DocumentTranslationOptions(DocumentType.GENERAL, TranslationStyle.NATURAL, List.of())))
                .isInstanceOf(InvalidAiResponseException.class)
                .hasMessageContaining("片段");
    }

    @Test
    void rejectsMoreThanOneHundredProtectedTermItemsBeforeDeduplication() {
        assertThatThrownBy(() -> new DocumentTranslationOptions(
                DocumentType.GENERAL, TranslationStyle.NATURAL, Collections.nCopies(101, "SDK")))
                .isInstanceOf(InvalidTranslationRequestException.class)
                .hasMessageContaining("100");
    }

    private TranslationParagraph paragraph(String id, String text) {
        return new TranslationParagraph(id, List.of(new StyledTextSegment("s0", text, "style")));
    }
}
