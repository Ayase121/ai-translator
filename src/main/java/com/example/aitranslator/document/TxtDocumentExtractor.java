package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class TxtDocumentExtractor implements DocumentExtractor {

    @Override
    public DocumentContent extract(byte[] bytes) {
        List<DocumentBlock> blocks = Arrays.stream(new String(bytes, StandardCharsets.UTF_8).split("\\R"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(DocumentBlock::paragraph)
                .toList();
        if (blocks.isEmpty()) {
            throw new InvalidDocumentException("文档中没有可翻译的文本");
        }
        return new DocumentContent(blocks);
    }
}
