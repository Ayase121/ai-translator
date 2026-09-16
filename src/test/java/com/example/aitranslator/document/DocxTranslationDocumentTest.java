package com.example.aitranslator.document;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocxTranslationDocumentTest {

    @Test
    void templateSchemeInputsParagraphHasNoBlankSegments() throws Exception {
        Path template = Path.of("C:/Users/a/Downloads/year-1-post-opening-report-template.docx");
        Assumptions.assumeTrue(Files.exists(template));

        DocxTranslationDocument document = DocxTranslationDocument.parse(Files.readAllBytes(template));
        DocxParagraph paragraph = document.paragraphs().stream()
                .filter(item -> item.id().equals("word/document.xml#p34"))
                .findFirst().orElseThrow();

        assertThat(paragraph.sourceText()).isEqualTo("Scheme inputs");
        assertThat(paragraph.segments()).isNotEmpty()
                .allSatisfy(segment -> assertThat(segment.sourceText()).isNotBlank());
    }
}
