package com.example.aitranslator.document;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TxtDocumentExtractorTest {

    @Test
    void extractsNonBlankLinesAsParagraphs() {
        DocumentContent content = new TxtDocumentExtractor().extract("标题\n\n第一段\n第二段".getBytes(StandardCharsets.UTF_8));

        assertThat(content.blocks()).extracting(DocumentBlock::text)
                .containsExactly("标题", "第一段", "第二段");
        assertThat(content.blocks()).extracting(DocumentBlock::type)
                .containsOnly(BlockType.PARAGRAPH);
    }
}
