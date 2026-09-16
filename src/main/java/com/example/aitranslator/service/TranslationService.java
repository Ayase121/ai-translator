package com.example.aitranslator.service;

import com.example.aitranslator.exception.InvalidTranslationRequestException;
import com.example.aitranslator.ai.TranslationModelGateway;
import com.example.aitranslator.ai.TranslationModelRequest;
import com.example.aitranslator.domain.Language;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TranslationService {

    private final TranslationModelGateway gateway;
    private final int maxTextLength;
    private final TextChunker textChunker;

    public TranslationService(
            TranslationModelGateway gateway,
            @Value("${app.translation.chunk-size:4000}") int chunkSize,
            @Value("${app.translation.max-text-length:20000}") int maxTextLength) {
        this.gateway = gateway;
        this.maxTextLength = maxTextLength;
        this.textChunker = new TextChunker(chunkSize);
    }

    public TranslationResult translateText(String text, Language sourceLanguage, Language targetLanguage) {
        if (text == null || text.isBlank()) {
            throw new InvalidTranslationRequestException("翻译文本不能为空");
        }
        if (text.length() > maxTextLength) {
            throw new InvalidTranslationRequestException("文本不能超过 " + maxTextLength + " 个字符");
        }
        if (targetLanguage == Language.AUTO) {
            throw new InvalidTranslationRequestException("目标语言不能为自动检测");
        }
        if (sourceLanguage != Language.AUTO && sourceLanguage == targetLanguage) {
            throw new InvalidTranslationRequestException("源语言和目标语言不能相同");
        }

        StringBuilder translated = new StringBuilder();
        for (String chunk : textChunker.chunk(text)) {
            translated.append(gateway.translate(new TranslationModelRequest(
                    chunk, sourceLanguage, targetLanguage)));
        }
        return new TranslationResult(translated.toString(), sourceLanguage, targetLanguage);
    }
}
