package com.example.aitranslator.service;

import com.example.aitranslator.exception.InvalidTranslationRequestException;
import com.example.aitranslator.ai.TranslationModelGateway;
import com.example.aitranslator.domain.Language;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranslationServiceTest {

    @Test
    void translatesTextThroughGateway() {
        TranslationModelGateway gateway = request -> "Hello world";
        TranslationService service = new TranslationService(gateway, 4_000, 20_000);

        TranslationResult result = service.translateText("你好，世界", Language.ZH_CN, Language.EN);

        assertThat(result.translatedText()).isEqualTo("Hello world");
        assertThat(result.sourceLanguage()).isEqualTo(Language.ZH_CN);
        assertThat(result.targetLanguage()).isEqualTo(Language.EN);
    }

    @Test
    void preservesChunkOrderForLongText() {
        List<String> received = new ArrayList<>();
        TranslationModelGateway gateway = request -> {
            received.add(request.text());
            return "[" + request.text() + "]";
        };
        TranslationService service = new TranslationService(gateway, 5, 20);

        TranslationResult result = service.translateText("abcdefghij", Language.AUTO, Language.EN);

        assertThat(received).containsExactly("abcde", "fghij");
        assertThat(result.translatedText()).isEqualTo("[abcde][fghij]");
    }

    @Test
    void rejectsSameExplicitSourceAndTarget() {
        TranslationService service = new TranslationService(request -> request.text(), 4_000, 20_000);

        assertThatThrownBy(() -> service.translateText("hello", Language.EN, Language.EN))
                .isInstanceOf(InvalidTranslationRequestException.class)
                .hasMessageContaining("不能相同");
    }
}
