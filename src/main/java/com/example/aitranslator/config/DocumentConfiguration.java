package com.example.aitranslator.config;

import com.example.aitranslator.document.DocumentExtractor;
import com.example.aitranslator.document.DocumentExtractorRegistry;
import com.example.aitranslator.document.DocxDocumentExtractor;
import com.example.aitranslator.document.DocxDocumentWriter;
import com.example.aitranslator.document.OcrService;
import com.example.aitranslator.document.PdfDocumentExtractor;
import com.example.aitranslator.document.PdfFixedLayoutDocxWriter;
import com.example.aitranslator.document.Tess4jOcrService;
import com.example.aitranslator.document.TxtDocumentExtractor;
import com.example.aitranslator.ai.ParagraphTranslationGateway;
import com.example.aitranslator.ai.TerminologyModelGateway;
import com.example.aitranslator.service.DocumentTranslationPipeline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class DocumentConfiguration {

    @Bean
    OcrService ocrService(@Value("${app.ocr.data-path:}") String dataPath) {
        return new Tess4jOcrService(dataPath);
    }

    @Bean
    DocumentExtractorRegistry documentExtractorRegistry(
            OcrService ocrService,
            @Value("${app.document.pdf-min-text-characters:20}") int minimumCharacters,
            @Value("${app.document.max-pages:100}") int maxPages,
            @Value("${app.ocr.dpi:300}") int dpi) {
        Map<String, DocumentExtractor> extractors = Map.of(
                "txt", new TxtDocumentExtractor(),
                "docx", new DocxDocumentExtractor(),
                "pdf", new PdfDocumentExtractor(ocrService, minimumCharacters, maxPages, dpi));
        return new DocumentExtractorRegistry(extractors);
    }

    @Bean
    DocxDocumentWriter docxDocumentWriter() {
        return new DocxDocumentWriter();
    }

    @Bean
    PdfFixedLayoutDocxWriter pdfFixedLayoutDocxWriter() {
        return new PdfFixedLayoutDocxWriter();
    }

    @Bean
    DocumentTranslationPipeline documentTranslationPipeline(
            TerminologyModelGateway terminologyGateway,
            ParagraphTranslationGateway paragraphGateway,
            @Value("${app.translation.glossary-chunk-size:12000}") int glossaryChunkSize,
            @Value("${app.translation.context-max-characters:2000}") int contextMaxCharacters) {
        return new DocumentTranslationPipeline(terminologyGateway, paragraphGateway,
                glossaryChunkSize, contextMaxCharacters);
    }
}
