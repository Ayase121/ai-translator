package com.example.aitranslator.ai;

import com.example.aitranslator.domain.DocumentType;
import com.example.aitranslator.domain.Language;
import com.example.aitranslator.domain.TranslationStyle;

import java.util.List;

public record ParagraphTranslationRequest(
        Language sourceLanguage,
        Language targetLanguage,
        DocumentType documentType,
        TranslationStyle translationStyle,
        List<GlossaryEntry> glossary,
        List<String> protectedTerms,
        String previousContext,
        List<StyledTextSegment> currentSegments) {

    public ParagraphTranslationRequest {
        glossary = List.copyOf(glossary == null ? List.of() : glossary);
        protectedTerms = List.copyOf(protectedTerms == null ? List.of() : protectedTerms);
        currentSegments = List.copyOf(currentSegments == null ? List.of() : currentSegments);
    }
}
