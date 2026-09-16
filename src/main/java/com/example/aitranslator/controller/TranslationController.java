package com.example.aitranslator.controller;

import com.example.aitranslator.domain.Language;
import com.example.aitranslator.dto.TextTranslationRequest;
import com.example.aitranslator.dto.TextTranslationResponse;
import com.example.aitranslator.service.TranslationResult;
import com.example.aitranslator.service.TranslationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/translations")
public class TranslationController {

    private final TranslationService translationService;


    @PostMapping("/text")
    public TextTranslationResponse translate(@Valid @RequestBody TextTranslationRequest request) {
        TranslationResult result = translationService.translateText(
                request.text(), Language.fromCode(request.sourceLanguage()), Language.fromCode(request.targetLanguage()));
        return new TextTranslationResponse(result.translatedText(), result.sourceLanguage().code(), result.targetLanguage().code());
    }
}