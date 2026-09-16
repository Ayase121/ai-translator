package com.example.aitranslator.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageServiceTest {

    @Test
    void listsAutoDetectionAndSupportedOcrLanguages() {
        var languages = new LanguageService().list();

        assertThat(languages).extracting("code")
                .contains("auto", "zh-CN", "en");
        assertThat(languages.stream().filter(language -> language.ocrSupported())
                .map(language -> language.code()).toList())
                .containsExactly("auto", "zh-CN", "en");
    }
}