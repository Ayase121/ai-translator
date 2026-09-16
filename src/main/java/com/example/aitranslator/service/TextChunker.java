package com.example.aitranslator.service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TextChunker {

    private final int maxCharacters;

    public TextChunker(int maxCharacters) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("分段长度必须大于 0");
        }
        this.maxCharacters = maxCharacters;
    }

    public List<String> chunk(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(text);
        StringBuilder current = new StringBuilder();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end);
            if (sentence.length() > maxCharacters) {
                flush(chunks, current);
                chunks.addAll(splitOversized(sentence));
            } else if (!current.isEmpty() && current.length() + sentence.length() > maxCharacters) {
                flush(chunks, current);
                current.append(sentence);
            } else {
                current.append(sentence);
            }
        }
        flush(chunks, current);
        return chunks;
    }

    private List<String> splitOversized(String value) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(start + maxCharacters, value.length());
            if (end < value.length() && end > start && Character.isHighSurrogate(value.charAt(end - 1))) {
                end--;
            }
            if (end == start) {
                end = Character.offsetByCodePoints(value, start, 1);
            }
            int whitespace = lastWhitespace(value, start, end);
            if (whitespace > start) {
                end = whitespace + 1;
            }
            chunks.add(value.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private int lastWhitespace(String value, int start, int end) {
        for (int index = end - 1; index > start; index--) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private void flush(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }
}
