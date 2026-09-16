package com.example.aitranslator.document;

import java.util.List;

/** A paragraph and its style-preserving text segments in one OOXML part. */
public final class DocxParagraph {

    private final String id;
    private final String partName;
    private final int xmlIndex;
    private final String sourceText;
    private final List<DocxTextSegment> segments;

    DocxParagraph(String id, String partName, int xmlIndex, String sourceText, List<DocxTextSegment> segments) {
        this.id = id;
        this.partName = partName;
        this.xmlIndex = xmlIndex;
        this.sourceText = sourceText;
        this.segments = List.copyOf(segments);
    }

    public String id() {
        return id;
    }

    public String partName() {
        return partName;
    }

    public int xmlIndex() {
        return xmlIndex;
    }

    public String sourceText() {
        return sourceText;
    }

    public List<DocxTextSegment> segments() {
        return segments;
    }
}
