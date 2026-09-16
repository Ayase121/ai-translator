package com.example.aitranslator.service;

import com.example.aitranslator.domain.Language;

public record TranslationResult(String translatedText, Language sourceLanguage, Language targetLanguage) {
}
