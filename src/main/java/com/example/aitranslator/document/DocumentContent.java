package com.example.aitranslator.document;

import java.util.List;
import java.util.Set;

public record DocumentContent(List<DocumentBlock> blocks, Set<Integer> ocrPages,
                              Set<Integer> unpositionedPages) {

    public DocumentContent {
        blocks = List.copyOf(blocks);
        ocrPages = Set.copyOf(ocrPages);
        unpositionedPages = Set.copyOf(unpositionedPages);
    }

    public DocumentContent(List<DocumentBlock> blocks, Set<Integer> ocrPages) {
        this(blocks, ocrPages, Set.of());
    }

    public DocumentContent(List<DocumentBlock> blocks) {
        this(blocks, Set.of());
    }

    public int characterCount() {
        return blocks.stream().mapToInt(block -> block.text().length()).sum();
    }
}
