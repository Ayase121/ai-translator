package com.example.aitranslator.ai;

import com.example.aitranslator.domain.DocumentType;
import com.example.aitranslator.domain.Language;
import com.example.aitranslator.domain.TranslationStyle;

import java.util.List;

public record TerminologyRequest(
        Language sourceLanguage,
        Language targetLanguage,
        DocumentType documentType,
        TranslationStyle translationStyle,
        List<String> protectedTerms,
        String text) {

    public TerminologyRequest {
        protectedTerms = List.copyOf(protectedTerms == null ? List.of() : protectedTerms);
    }
}
