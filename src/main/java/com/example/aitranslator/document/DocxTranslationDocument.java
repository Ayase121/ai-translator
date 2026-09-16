package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Reads a DOCX package without reconstructing it and applies text-only edits to
 * the visible WordprocessingML parts.
 */
public final class DocxTranslationDocument {

    private static final Logger log = LoggerFactory.getLogger(DocxTranslationDocument.class);
    static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final Set<String> STRUCTURAL_NAMES = Set.of("tab", "br", "cr", "lastRenderedPageBreak");
    private static final Pattern TRANSLATABLE_PART = Pattern.compile(
            "^word/(document|header\\d+|footer\\d+|footnotes|endnotes)\\.xml$");
    private static final Predicate<Node> TEXT_NODE = node ->
            W_NS.equals(node.getNamespaceURI()) && "t".equals(node.getLocalName());
    private static final Predicate<Node> STRUCTURAL_NODE = node ->
            W_NS.equals(node.getNamespaceURI()) && STRUCTURAL_NAMES.contains(node.getLocalName());

    private final LinkedHashMap<String, byte[]> entries;
    private final LinkedHashMap<String, Document> xmlParts;
    private final List<DocxParagraph> paragraphs;
    private final Map<String, DocxParagraph> paragraphsById;

    private DocxTranslationDocument(LinkedHashMap<String, byte[]> entries,
                                    LinkedHashMap<String, Document> xmlParts,
                                    List<DocxParagraph> paragraphs) {
        this.entries = entries;
        this.xmlParts = xmlParts;
        this.paragraphs = List.copyOf(paragraphs);
        this.paragraphsById = new LinkedHashMap<>();
        paragraphs.forEach(paragraph -> paragraphsById.put(paragraph.id(), paragraph));
    }

    public static DocxTranslationDocument parse(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        LinkedHashMap<String, Document> xmlParts = new LinkedHashMap<>();
        List<DocxParagraph> paragraphs = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] content = input.readAllBytes();
                entries.put(entry.getName(), content);
                if (TRANSLATABLE_PART.matcher(entry.getName()).matches()) {
                    Document xml = parseXml(content);
                    xmlParts.put(entry.getName(), xml);
                    collectParagraphs(entry.getName(), xml, paragraphs);
                }
            }
        } catch (Exception exception) {
            throw new InvalidDocumentException("无法解析 DOCX 文档", exception);
        }
        if (!entries.containsKey("word/document.xml")) {
            throw new InvalidDocumentException("DOCX 缺少主文档部件");
        }
        if (paragraphs.isEmpty()) {
            throw new InvalidDocumentException("文档中没有可翻译的文本");
        }
        return new DocxTranslationDocument(entries, xmlParts, paragraphs);
    }

    public List<DocxParagraph> paragraphs() {
        return paragraphs;
    }

    /**
     * Applies only the supplied segment translations. Missing paragraphs remain
     * untouched, which allows callers to stage all model responses before the
     * final atomic write.
     */
    public byte[] write(Map<String, Map<String, String>> translations) {
        Objects.requireNonNull(translations, "translations");
        Set<String> modifiedParts = new LinkedHashSet<>();
        translations.forEach((paragraphId, segmentTranslations) -> {
            DocxParagraph paragraph = paragraphsById.get(paragraphId);
            if (paragraph == null) {
                throw new InvalidDocumentException("翻译结果包含未知段落: " + paragraphId);
            }
            Map<String, String> values = segmentTranslations == null ? Collections.emptyMap() : segmentTranslations;
            paragraph.segments().forEach(segment -> {
                if (!values.containsKey(segment.id())) {
                    return;
                }
                String translated = values.get(segment.id());
                if (translated == null) {
                    throw new InvalidDocumentException("翻译结果包含空片段: " + segment.id());
                }
                List<Element> nodes = segment.textNodes();
                if (nodes.isEmpty()) {
                    return;
                }
                nodes.get(0).setTextContent(translated);
                for (int index = 1; index < nodes.size(); index++) {
                    nodes.get(index).setTextContent("");
                }
                modifiedParts.add(paragraph.partName());
            });
        });

        LinkedHashMap<String, byte[]> outputEntries = new LinkedHashMap<>(entries);
        try {
            for (Map.Entry<String, Document> part : xmlParts.entrySet()) {
                if (modifiedParts.contains(part.getKey())) {
                    outputEntries.put(part.getKey(), serializeXml(part.getValue()));
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (Map.Entry<String, byte[]> entry : outputEntries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new InvalidDocumentException("无法写回 DOCX 文档", exception);
        }
    }

    private static void collectParagraphs(String partName, Document xml, List<DocxParagraph> target) {
        NodeList nodes = xml.getElementsByTagNameNS(W_NS, "p");
        int partParagraphIndex = 0;
        for (int index = 0; index < nodes.getLength(); index++) {
            Element paragraph = (Element) nodes.item(index);
            List<Node> ordered = descendants(paragraph);
            List<DocxTextSegment> segments = new ArrayList<>();
            StringBuilder source = new StringBuilder();
            SegmentBuilder current = null;
            boolean barrier = false;
            for (Node node : ordered) {
                if (STRUCTURAL_NODE.test(node)) {
                    barrier = true;
                    continue;
                }
                if (!TEXT_NODE.test(node) || !isEligibleText(node, paragraph)) {
                    continue;
                }
                Element text = (Element) node;
                source.append(text.getTextContent());
                Element run = (Element) nearestAncestor(text, "r");
                String style = styleSignature(run == null ? null : child(run, "rPr"));
                if (current == null || barrier || !current.style.equals(style)) {
                    if (current != null) {
                        addTranslatableSegment(segments, current, partName, partParagraphIndex);
                    }
                    current = new SegmentBuilder(style);
                    barrier = false;
                }
                current.append(text);
            }
            if (current != null) {
                addTranslatableSegment(segments, current, partName, partParagraphIndex);
            }
            if (!segments.isEmpty()) {
                String id = partName + "#p" + partParagraphIndex;
                if (!source.isEmpty()) {
                    target.add(new DocxParagraph(id, partName, partParagraphIndex, source.toString(), segments));
                    if (log.isDebugEnabled()) {
                        log.debug("DOCX paragraph {} extracted (textLength={}, segments={})",
                                id, source.length(), segments.size());
                        segments.forEach(segment -> log.debug(
                                "DOCX paragraph {} segment {} (textLength={}, style={})",
                                id, segment.id(), segment.sourceText().length(), segment.styleSignature()));
                    }
                }
            }
            partParagraphIndex++;
        }
    }

    private static void addTranslatableSegment(List<DocxTextSegment> segments, SegmentBuilder builder,
                                               String partName, int paragraphIndex) {
        if (!builder.source.toString().isBlank()) {
            segments.add(builder.finish(partName, paragraphIndex, segments.size()));
        }
    }

    private static boolean isEligibleText(Node text, Element paragraph) {
        Node run = nearestAncestor(text, "r");
        if (run == null || nearestAncestor(run, "del") != null || nearestAncestor(run, "moveFrom") != null) {
            return false;
        }
        return nearestAncestor(text, "p") == paragraph;
    }

    private static List<Node> descendants(Element root) {
        List<Node> nodes = new ArrayList<>();
        visit(root, nodes);
        return nodes;
    }

    private static void visit(Node node, List<Node> target) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                target.add(child);
                visit(child, target);
            }
        }
    }

    private static Node nearestAncestor(Node node, String localName) {
        Node current = node.getParentNode();
        while (current != null) {
            if (W_NS.equals(current.getNamespaceURI()) && localName.equals(current.getLocalName())) {
                return current;
            }
            current = current.getParentNode();
        }
        return null;
    }

    private static Element child(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (W_NS.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) {
                return (Element) child;
            }
        }
        return null;
    }

    private static String styleSignature(Element rPr) {
        if (rPr == null) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        appendNodeSignature(rPr, value);
        return value.toString();
    }

    private static void appendNodeSignature(Node node, StringBuilder value) {
        value.append('{').append(node.getNodeName());
        if (node.hasAttributes()) {
            List<String> attributes = new ArrayList<>();
            for (int index = 0; index < node.getAttributes().getLength(); index++) {
                Node attribute = node.getAttributes().item(index);
                attributes.add(attribute.getNodeName() + '=' + attribute.getNodeValue());
            }
            attributes.sort(String::compareTo);
            attributes.forEach(attribute -> value.append('|').append(attribute));
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                appendNodeSignature(child, value);
            }
        }
        value.append('}');
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static byte[] serializeXml(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    private static final class SegmentBuilder {
        private final String style;
        private final List<Element> textNodes = new ArrayList<>();
        private final StringBuilder source = new StringBuilder();

        private SegmentBuilder(String style) {
            this.style = style;
        }

        private void append(Element text) {
            textNodes.add(text);
            source.append(text.getTextContent());
        }

        private DocxTextSegment finish(String partName, int paragraphIndex, int segmentIndex) {
            return new DocxTextSegment("%s#p%d#s%d".formatted(partName, paragraphIndex, segmentIndex),
                    source.toString(), style, textNodes);
        }
    }
}
