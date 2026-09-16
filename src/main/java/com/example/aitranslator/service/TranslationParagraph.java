package com.example.aitranslator.service;

import com.example.aitranslator.ai.StyledTextSegment;

import java.util.List;

public record TranslationParagraph(String id, List<StyledTextSegment> segments) {

    public TranslationParagraph {
        segments = List.copyOf(segments);
    }

    public String sourceText() {
        return segments.stream().map(StyledTextSegment::sourceText).reduce("", String::concat);
    }
}
