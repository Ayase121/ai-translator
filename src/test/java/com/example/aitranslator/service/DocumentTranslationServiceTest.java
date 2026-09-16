package com.example.aitranslator.service;

import com.example.aitranslator.ai.TranslationModelGateway;
import com.example.aitranslator.exception.InvalidAiResponseException;
import com.example.aitranslator.ai.ParagraphTranslationGateway;
import com.example.aitranslator.document.DocumentExtractorRegistry;
import com.example.aitranslator.document.DocumentFileValidator;
import com.example.aitranslator.document.DocxDocumentWriter;
import com.example.aitranslator.document.PdfDocumentExtractor;
import com.example.aitranslator.document.TxtDocumentExtractor;
import com.example.aitranslator.domain.Language;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTranslationServiceTest {

    @Test
    void translatesTxtBlocksAndReturnsDocx() throws Exception {
        TranslationModelGateway gateway = request -> "译:" + request.text();
        DocumentExtractorRegistry registry = new DocumentExtractorRegistry(Map.of("txt", new TxtDocumentExtractor()));
        DocumentTranslationService service = new DocumentTranslationService(
                new DocumentFileValidator(10_000), registry, new DocxDocumentWriter(), gateway, 100_000, 4_000);
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "one\ntwo".getBytes(StandardCharsets.UTF_8));

        TranslatedDocument result = service.translate(file, Language.EN, Language.ZH_CN);

        assertThat(result.filename()).isEqualTo("notes-translated.docx");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(result.content()))) {
            assertThat(document.getParagraphs()).extracting(paragraph -> paragraph.getText())
                    .containsExactly("译:one", "译:two");
        }
    }

    @Test
    void translatesRealDocxParagraphOnceEvenWhenItHasEightRuns() throws Exception {
        List<Integer> requestLengths = new ArrayList<>();
        TranslationModelGateway gateway = request -> {
            requestLengths.add(request.text().length());
            return "translated";
        };
        DocumentTranslationService service = new DocumentTranslationService(
                new DocumentFileValidator(10_000_000), new DocumentExtractorRegistry(Map.of()),
                new DocxDocumentWriter(), gateway, 100_000, 4_000);
        MockMultipartFile file;
        try (var input = getClass().getResourceAsStream("/fixtures/REDACTED_09.02.16_Style__Guidence_for_documents.docx")) {
            file = new MockMultipartFile("file", "guidance.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", input.readAllBytes());
        }

        service.translate(file, Language.EN, Language.ZH_CN);

        assertThat(requestLengths).contains(130);
        assertThat(requestLengths.stream().filter(length -> length == 130)).hasSize(1);
    }

    @Test
    void recoversFailedWordBatchBySplittingAndReturnsReadableDocx() throws Exception {
        int[] translatedParagraphs = {0};
        ParagraphTranslationGateway model = request -> {
            translatedParagraphs[0]++;
            if (translatedParagraphs[0] == 2) {
                throw new InvalidAiResponseException("模型片段无效",
                        InvalidAiResponseException.Reason.SEGMENT_MAPPING);
            }
            Map<String, String> result = new java.util.LinkedHashMap<>();
            request.currentSegments().forEach(segment -> result.put(segment.id(), "译文"));
            return result;
        };
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(
                request -> List.of(), model, 4_000, 2_000);
        DocumentTranslationService service = new DocumentTranslationService(
                new DocumentFileValidator(10_000_000), new DocumentExtractorRegistry(Map.of()),
                new DocxDocumentWriter(), pipeline, 100_000);
        byte[] source;
        try (var input = getClass().getResourceAsStream("/fixtures/REDACTED_09.02.16_Style__Guidence_for_documents.docx")) {
            source = input.readAllBytes();
        }
        MockMultipartFile file = new MockMultipartFile("file", "guidance.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", source);

        TranslatedDocument result = service.translate(file, Language.EN, Language.ZH_CN);
        assertThat(translatedParagraphs[0]).isGreaterThan(2);
        assertThat(result.partial()).isFalse();
        assertThat(result.content()).isNotEmpty();
        assertThat(file.getBytes()).isEqualTo(source);
    }

    @Test
    void doesNotSendPdfEquationsOrInlineVariablesToTranslationModel() throws Exception {
        List<String> modelInputs = new ArrayList<>();
        TranslationModelGateway gateway = request -> {
            modelInputs.add(request.text());
            return "这是一段完整的中文翻译。";
        };
        DocumentTranslationService service = new DocumentTranslationService(
                new DocumentFileValidator(10_000_000),
                new DocumentExtractorRegistry(Map.of("pdf", new PdfDocumentExtractor(image -> "", 20, 100, 72))),
                new DocxDocumentWriter(), gateway, 100_000, 4_000);
        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf",
                pdfWithLines("The article evaluates translation quality.", "CTT = x + y",
                        "The score uses t1 = t2 for consistency."));

        TranslatedDocument result = service.translate(file, Language.EN, Language.ZH_CN);

        assertThat(modelInputs).isNotEmpty();
        assertThat(modelInputs).noneMatch(input -> input.contains("t1 = t2") || input.contains("CTT = x + y"));
        try (XWPFDocument output = new XWPFDocument(new ByteArrayInputStream(result.content()))) {
            assertThat(output.getAllPictures()).hasSize(1);
        }
        assertThat(result.partial()).isFalse();
    }

    @Test
    void includesPdfPageNumberInModelRequestIdentifiers() throws Exception {
        List<String> segmentIds = new ArrayList<>();
        ParagraphTranslationGateway model = request -> {
            Map<String, String> translations = new java.util.LinkedHashMap<>();
            request.currentSegments().forEach(segment -> {
                segmentIds.add(segment.id());
                translations.put(segment.id(), "译文");
            });
            return translations;
        };
        DocumentTranslationPipeline pipeline = new DocumentTranslationPipeline(
                request -> List.of(), model, 4_000, 2_000);
        DocumentTranslationService service = new DocumentTranslationService(
                new DocumentFileValidator(10_000_000),
                new DocumentExtractorRegistry(Map.of("pdf", new PdfDocumentExtractor(image -> "", 20, 100, 72))),
                new DocxDocumentWriter(), pipeline, 100_000);
        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf",
                pdfWithLines("This paper studies translation quality."));

        service.translate(file, Language.EN, Language.ZH_CN);

        assertThat(segmentIds).isNotEmpty().allMatch(id -> id.startsWith("pdf-page-1#"));
    }

    @Test
    void rejectsPdfWhenModelParaphrasesEnglishInsteadOfTranslatingToChinese() throws Exception {
        ParagraphTranslationGateway model = request -> {
            Map<String, String> result = new java.util.LinkedHashMap<>();
            request.currentSegments().forEach(segment -> result.put(segment.id(),
                    "This manuscript examines the quality of translation."));
            return result;
        };
        DocumentTranslationService service = new DocumentTranslationService(
                new DocumentFileValidator(10_000_000),
                new DocumentExtractorRegistry(Map.of("pdf", new PdfDocumentExtractor(image -> "", 20, 100, 72))),
                new DocxDocumentWriter(), new DocumentTranslationPipeline(request -> List.of(), model, 4_000, 2_000),
                100_000);
        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf",
                pdfWithLines("This paper studies translation quality."));

        assertThatThrownBy(() -> service.translate(file, Language.EN, Language.ZH_CN))
                .isInstanceOf(InvalidAiResponseException.class);
    }

    @Test
    void rejectsOneChineseCharacterPrefixedToUntranslatedEnglish() throws Exception {
        TranslationModelGateway gateway = request -> "译:" + request.text();
        DocumentTranslationService service = new DocumentTranslationService(
                new DocumentFileValidator(10_000_000),
                new DocumentExtractorRegistry(Map.of("pdf", new PdfDocumentExtractor(image -> "", 20, 100, 72))),
                new DocxDocumentWriter(), gateway, 100_000, 4_000);
        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf",
                pdfWithLines("The manuscript evaluates document translation quality across multiple domains."));

        assertThatThrownBy(() -> service.translate(file, Language.EN, Language.ZH_CN))
                .isInstanceOf(InvalidAiResponseException.class);
    }

    @Test
    void keepsOcrPageAndDoesNotSendItsPossibleFormulaErrorsToModel() throws Exception {
        byte[] mixedPdf;
        try (PDDocument source = org.apache.pdfbox.Loader.loadPDF(
                pdfWithLines("This paper studies document translation quality."));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            source.addPage(new PDPage());
            source.save(output);
            mixedPdf = output.toByteArray();
        }
        PdfDocumentExtractor extractor = new PdfDocumentExtractor(
                image -> "OCR may confuse x2 = y3 in a scanned equation.", 20, 100, 72);
        assertThat(extractor.extract(mixedPdf).ocrPages()).containsExactly(2);
        List<String> modelInputs = new ArrayList<>();
        TranslationModelGateway model = request -> {
            modelInputs.add(request.text());
            return "这是中文译文。";
        };
        DocumentTranslationService service = new DocumentTranslationService(
                new DocumentFileValidator(10_000_000),
                new DocumentExtractorRegistry(Map.of("pdf", extractor)), new DocxDocumentWriter(),
                model, 100_000, 4_000);

        TranslatedDocument result = service.translate(
                new MockMultipartFile("file", "mixed.pdf", "application/pdf", mixedPdf),
                Language.EN, Language.ZH_CN);

        assertThat(modelInputs).hasSize(1).noneMatch(text -> text.contains("x2 = y3"));
        assertThat(result.partial()).isTrue();
        try (XWPFDocument output = new XWPFDocument(new ByteArrayInputStream(result.content()))) {
            assertThat(output.getAllPictures()).hasSize(2);
        }
    }

    private byte[] pdfWithLines(String... lines) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(0))) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                for (String line : lines) {
                    stream.showText(line);
                    stream.newLineAtOffset(0, -18);
                }
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
