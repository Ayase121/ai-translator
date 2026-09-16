package com.example.aitranslator.service;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

public record DocumentTranslationResult(Map<String, Map<String, String>> translations) {

    public DocumentTranslationResult {
        Map<String, Map<String, String>> ordered = new LinkedHashMap<>();
        translations.forEach((id, segments) ->
                ordered.put(id, Collections.unmodifiableMap(new LinkedHashMap<>(segments))));
        translations = Collections.unmodifiableMap(ordered);
    }
}
