package com.example.aitranslator.service;

public record TranslatedDocument(String filename, byte[] content, boolean partial,
                                 int eligibleLines, int coveredLines) {
    public TranslatedDocument(String filename, byte[] content, boolean partial) {
        this(filename, content, partial, 0, 0);
    }

    public TranslatedDocument(String filename, byte[] content) {
        this(filename, content, false);
    }
}
