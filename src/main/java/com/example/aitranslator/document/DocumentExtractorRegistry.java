package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import java.util.Map;

public class DocumentExtractorRegistry {

    private final Map<String, DocumentExtractor> extractors;

    public DocumentExtractorRegistry(Map<String, DocumentExtractor> extractors) {
        this.extractors = Map.copyOf(extractors);
    }

    public DocumentExtractor get(String extension) {
        DocumentExtractor extractor = extractors.get(extension);
        if (extractor == null) {
            throw new InvalidDocumentException("找不到对应的文档解析器");
        }
        return extractor;
    }
}
