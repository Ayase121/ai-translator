package com.example.aitranslator.service;

import com.example.aitranslator.document.PdfProtectedSpan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;

/** Hides inline identifiers and mathematical expressions from model requests. */
final class PdfMathTextProtector {
    private PdfMathTextProtector() {
    }

    static ProtectedText protect(String source) {
        Matcher matcher = PdfProtectedSpan.matcher(source);
        Map<String, String> originals = new LinkedHashMap<>();
        StringBuilder masked = new StringBuilder();
        int position = 0;
        while (matcher.find()) {
            masked.append(source, position, matcher.start());
            int tokenNumber = originals.size();
            String token = "__PDF_KEEP_" + tokenNumber + "__";
            while (source.contains(token) || originals.containsKey(token)) {
                token = "__PDF_KEEP_" + ++tokenNumber + "__";
            }
            originals.put(token, matcher.group());
            masked.append(token);
            position = matcher.end();
        }
        masked.append(source, position, source.length());
        return new ProtectedText(source, masked.toString(), Map.copyOf(originals));
    }

    record ProtectedText(String source, String masked, Map<String, String> originals) {
        Optional<String> restore(String translation) {
            if (translation == null || translation.isBlank()) {
                return Optional.empty();
            }
            String restored = translation;
            for (Map.Entry<String, String> entry : originals.entrySet()) {
                String token = entry.getKey();
                int first = restored.indexOf(token);
                if (first < 0 || restored.indexOf(token, first + token.length()) >= 0) {
                    return Optional.empty();
                }
                restored = restored.replace(token, entry.getValue());
            }
            return Optional.of(restored);
        }
    }
}
