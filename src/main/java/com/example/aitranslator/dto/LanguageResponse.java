package com.example.aitranslator.dto;

public record LanguageResponse(String code, String name, boolean sourceOnly, boolean ocrSupported) {
}