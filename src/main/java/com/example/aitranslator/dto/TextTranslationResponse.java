package com.example.aitranslator.dto;

public record TextTranslationResponse(String translatedText, String sourceLanguage, String targetLanguage) {
}