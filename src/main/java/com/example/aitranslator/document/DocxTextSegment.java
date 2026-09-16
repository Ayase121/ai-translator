package com.example.aitranslator.document;

import org.w3c.dom.Element;

import java.util.List;

/** A logical, style-homogeneous group of DOCX text nodes. */
public final class DocxTextSegment {

    private final String id;
    private final String sourceText;
    private final String styleSignature;
    private final List<Element> textNodes;

    DocxTextSegment(String id, String sourceText, String styleSignature, List<Element> textNodes) {
        this.id = id;
        this.sourceText = sourceText;
        this.styleSignature = styleSignature;
        this.textNodes = List.copyOf(textNodes);
    }

    public String id() {
        return id;
    }

    public String sourceText() {
        return sourceText;
    }

    public String styleSignature() {
        return styleSignature;
    }

    public int textNodeCount() {
        return textNodes.size();
    }

    List<Element> textNodes() {
        return textNodes;
    }
}
