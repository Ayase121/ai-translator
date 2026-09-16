package com.example.aitranslator;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest.Thinking;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.deepseek.api-key=test-key",
        "app.ocr.data-path="
})
class AiTranslatorApplicationTest {

    @Autowired
    private DeepSeekChatProperties chatProperties;

    @Test
    void contextLoads() {
        assertThat(chatProperties.getModel()).isEqualTo("deepseek-flash");
        assertThat(chatProperties.getTemperature()).isEqualTo(0.1);
        assertThat(chatProperties.getThinking()).isEqualTo(Thinking.DISABLED);
    }
}
