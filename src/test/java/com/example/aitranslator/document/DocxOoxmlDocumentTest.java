package com.example.aitranslator.document;

import com.example.aitranslator.ai.DeepSeekParagraphTranslationGateway;
import com.example.aitranslator.ai.ParagraphTranslationRequest;
import com.example.aitranslator.ai.StyledTextSegment;
import com.example.aitranslator.domain.DocumentType;
import com.example.aitranslator.domain.Language;
import com.example.aitranslator.domain.TranslationStyle;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DocxOoxmlDocumentTest {

    private static final String FIXTURE = "/fixtures/REDACTED_09.02.16_Style__Guidence_for_documents.docx";

    @Test
    void groupsEightSameStyleRunsIntoOneParagraphSegment() throws Exception {
        DocxTranslationDocument document = DocxTranslationDocument.parse(fixture());

        DocxParagraph paragraph = document.paragraphs().stream()
                .filter(candidate -> candidate.segments().stream().anyMatch(segment -> segment.textNodeCount() == 8))
                .findFirst().orElseThrow();

        assertThat(paragraph.segments()).hasSize(1);
        assertThat(paragraph.segments().get(0).textNodeCount()).isEqualTo(8);
        assertThat(paragraph.sourceText()).hasSize(130);
    }

    @Test
    void keepsMixedStyleRunsAsSeparateSegments() throws Exception {
        DocxTranslationDocument document = DocxTranslationDocument.parse(fixture());

        assertThat(document.paragraphs()).anySatisfy(paragraph -> {
            assertThat(paragraph.segments()).hasSizeGreaterThan(1);
            assertThat(paragraph.segments()).extracting(DocxTextSegment::styleSignature)
                    .doesNotHaveDuplicates();
        });
    }

    @Test
    void leavesStyleIsolatedWhitespaceOutOfTranslationAndPreservesItInDocx() throws Exception {
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p>
                    <w:r><w:t xml:space="preserve">[Free text.] </w:t></w:r>
                    <w:r><w:rPr><w:i/><w:color w:val="FF0000"/></w:rPr>
                      <w:t>[Explain the legislative context.]</w:t></w:r>
                    <w:r><w:t xml:space="preserve"> </w:t></w:r>
                  </w:p></w:body>
                </w:document>
                """;
        DocxTranslationDocument document = DocxTranslationDocument.parse(minimalPackage(documentXml));
        DocxParagraph paragraph = document.paragraphs().get(0);

        assertThat(paragraph.sourceText()).isEqualTo("[Free text.] [Explain the legislative context.] ");
        assertThat(paragraph.segments()).extracting(DocxTextSegment::sourceText)
                .containsExactly("[Free text.] ", "[Explain the legislative context.]");

        byte[] output = document.write(Map.of(paragraph.id(), Map.of(
                paragraph.segments().get(0).id(), "[自由填写] ",
                paragraph.segments().get(1).id(), "[说明立法背景]")));
        org.w3c.dom.Document xml = xml(zipEntries(output).get("word/document.xml"));
        NodeList texts = xml.getElementsByTagNameNS(DocxTranslationDocument.W_NS, "t");
        assertThat(texts.getLength()).isEqualTo(3);
        assertThat(texts.item(0).getTextContent()).isEqualTo("[自由填写] ");
        assertThat(texts.item(1).getTextContent()).isEqualTo("[说明立法背景]");
        assertThat(texts.item(2).getTextContent()).isEqualTo(" ");
    }

    @Test
    void translatesFixtureParagraphP18OnceAndWritesOnlyItsTextSlot() throws Exception {
        byte[] source = fixture();
        DocxTranslationDocument document = DocxTranslationDocument.parse(source);
        DocxParagraph paragraph = document.paragraphs().stream()
                .filter(candidate -> candidate.id().equals("word/document.xml#p18"))
                .findFirst().orElseThrow();
        assertThat(paragraph.segments()).hasSize(1);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec call = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(call);
        when(call.system(anyString())).thenReturn(call);
        when(call.user(anyString())).thenReturn(call);
        when(call.call()).thenReturn(response);
        when(response.content()).thenReturn("中文段落译文");

        DocxTextSegment segment = paragraph.segments().get(0);
        DeepSeekParagraphTranslationGateway gateway = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper());
        Map<String, String> translated = gateway.translate(new ParagraphTranslationRequest(
                Language.EN, Language.ZH_CN, DocumentType.GENERAL, TranslationStyle.NATURAL,
                List.of(), List.of(), "", List.of(new StyledTextSegment(
                segment.id(), segment.sourceText(), segment.styleSignature()))));
        byte[] output = document.write(Map.of(paragraph.id(), translated));

        verify(response, times(1)).content();
        Map<String, byte[]> before = zipEntries(source);
        Map<String, byte[]> after = zipEntries(output);
        for (Map.Entry<String, byte[]> entry : before.entrySet()) {
            if (!entry.getKey().equals("word/document.xml")) {
                assertThat(sha256(after.get(entry.getKey()))).isEqualTo(sha256(entry.getValue()));
            }
        }
        org.w3c.dom.Document xml = xml(after.get("word/document.xml"));
        Node target = xml.getElementsByTagNameNS(DocxTranslationDocument.W_NS, "p").item(paragraph.xmlIndex());
        NodeList textNodes = ((org.w3c.dom.Element) target).getElementsByTagNameNS(DocxTranslationDocument.W_NS, "t");
        assertThat(textNodes.getLength()).isEqualTo(1);
        assertThat(textNodes.item(0).getTextContent()).isEqualTo("中文段落译文");
    }

    @Test
    void changesOnlySelectedTextAndPreservesOtherZipEntries() throws Exception {
        byte[] source = fixture();
        DocxTranslationDocument document = DocxTranslationDocument.parse(source);
        DocxParagraph paragraph = document.paragraphs().stream()
                .filter(candidate -> candidate.segments().stream().anyMatch(segment -> segment.textNodeCount() == 8))
                .findFirst().orElseThrow();
        DocxTextSegment segment = paragraph.segments().get(0);

        byte[] translated = document.write(Map.of(
                paragraph.id(), Map.of(segment.id(), "translated paragraph")));

        Map<String, byte[]> before = zipEntries(source);
        Map<String, byte[]> after = zipEntries(translated);
        assertThat(after.keySet()).containsExactlyInAnyOrderElementsOf(before.keySet());
        for (Map.Entry<String, byte[]> entry : before.entrySet()) {
            if (!entry.getKey().equals(paragraph.partName())) {
                assertThat(sha256(after.get(entry.getKey()))).isEqualTo(sha256(entry.getValue()));
            }
        }

        org.w3c.dom.Document xml = xml(after.get(paragraph.partName()));
        NodeList paragraphs = xml.getElementsByTagNameNS(DocxTranslationDocument.W_NS, "p");
        Node target = paragraphs.item(paragraph.xmlIndex());
        NodeList textNodes = ((org.w3c.dom.Element) target).getElementsByTagNameNS(DocxTranslationDocument.W_NS, "t");
        NodeList runs = ((org.w3c.dom.Element) target).getElementsByTagNameNS(DocxTranslationDocument.W_NS, "r");
        assertThat(textNodes.getLength()).isEqualTo(8);
        assertThat(runs.getLength()).isEqualTo(8);
        assertThat(target.getTextContent()).contains("translated paragraph");
    }

    @Test
    void discoversTextBoxParagraphsAsIndependentTranslationUnits() {
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>Body</w:t><w:pict><w:txbxContent>
                    <w:p><w:r><w:t>Text box</w:t></w:r></w:p>
                  </w:txbxContent></w:pict></w:r></w:p></w:body>
                </w:document>
                """;

        DocxTranslationDocument document = DocxTranslationDocument.parse(minimalPackage(documentXml));

        assertThat(document.paragraphs()).extracting(DocxParagraph::sourceText)
                .containsExactly("Body", "Text box");
    }

    private byte[] fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(FIXTURE)) {
            assertThat(input).as("fixture must be copied into test resources").isNotNull();
            return input.readAllBytes();
        }
    }

    private Map<String, byte[]> zipEntries(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                input.transferTo(output);
                entries.put(entry.getName(), output.toByteArray());
            }
        }
        return entries;
    }

    private byte[] minimalPackage(String documentXml) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry("word/document.xml"));
                zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private org.w3c.dom.Document xml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return new String(java.util.HexFormat.of().formatHex(digest).getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }
}
