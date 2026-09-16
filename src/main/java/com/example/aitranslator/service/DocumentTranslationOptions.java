package com.example.aitranslator.service;

import com.example.aitranslator.exception.InvalidTranslationRequestException;
import com.example.aitranslator.domain.DocumentType;
import com.example.aitranslator.domain.TranslationStyle;

import java.util.ArrayList;
import java.util.List;

public record DocumentTranslationOptions(
        DocumentType documentType,
        TranslationStyle translationStyle,
        List<String> protectedTerms) {

    public DocumentTranslationOptions {
        documentType = documentType == null ? DocumentType.GENERAL : documentType;
        translationStyle = translationStyle == null ? TranslationStyle.NATURAL : translationStyle;
        List<String> normalized = new ArrayList<>();
        List<String> requestedTerms = protectedTerms == null ? List.of() : protectedTerms;
        if (requestedTerms.size() > 100) {
            throw new InvalidTranslationRequestException("保护词不能超过 100 个");
        }
        for (String term : requestedTerms) {
            if (term != null && !term.isBlank() && !normalized.contains(term.strip())) {
                normalized.add(term.strip());
            }
        }
        if (normalized.stream().anyMatch(term -> term.length() > 100)) {
            throw new InvalidTranslationRequestException("单个保护词不能超过 100 个字符");
        }
        protectedTerms = List.copyOf(normalized);
    }
}
