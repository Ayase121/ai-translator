package com.example.aitranslator.service;

import com.example.aitranslator.domain.Language;
import com.example.aitranslator.dto.LanguageResponse;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class LanguageService {

    public List<LanguageResponse> list() {
        return Arrays.stream(Language.values())
                .map(language -> new LanguageResponse(language.code(), language.displayName(),
                        language == Language.AUTO, language.ocrSupported()))
                .toList();
    }
}