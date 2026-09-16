package com.example.aitranslator.ai;

import com.example.aitranslator.domain.Language;

public record TranslationModelRequest(String text, Language sourceLanguage, Language targetLanguage) {
}
