package com.example.aitranslator.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    @Test
    void prefersSentenceBoundariesAndPreservesAllCharacters() {
        TextChunker chunker = new TextChunker(8);
        String text = "Hello. 世界！Next.";

        assertThat(chunker.chunk(text)).containsExactly("Hello. ", "世界！Next.");
        assertThat(String.join("", chunker.chunk(text))).isEqualTo(text);
    }

    @Test
    void neverSplitsUtf16SurrogatePair() {
        TextChunker chunker = new TextChunker(2);

        assertThat(chunker.chunk("A😀B")).containsExactly("A", "😀", "B");
    }
}
