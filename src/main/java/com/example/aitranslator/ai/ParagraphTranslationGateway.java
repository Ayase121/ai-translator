package com.example.aitranslator.ai;

import java.util.Map;

public interface ParagraphTranslationGateway {

    Map<String, String> translate(ParagraphTranslationRequest request);

    default Map<String, String> translate(ParagraphTranslationRequest request, ParagraphRequestMetrics metrics) {
        metrics.mainRequest();
        return translate(request);
    }
}
