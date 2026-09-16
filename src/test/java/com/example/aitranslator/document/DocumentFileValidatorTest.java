package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFileValidatorTest {

    private final DocumentFileValidator validator = new DocumentFileValidator(10 * 1024 * 1024);

    @Test
    void acceptsTxtDocxAndPdfSignatures() {
        assertThatCode(() -> validator.validate(file("note.txt", "text/plain", "hello".getBytes())))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(file("book.pdf", "application/pdf", "%PDF-1.7".getBytes())))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(file("book.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{'P', 'K', 3, 4})))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMismatchedFileSignature() {
        assertThatThrownBy(() -> validator.validate(file("fake.pdf", "application/pdf", "not a pdf".getBytes())))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("签名");
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> validator.validate(file("sheet.xlsx", "application/octet-stream", new byte[]{1, 2, 3})))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("TXT、DOCX、PDF");
    }

    private MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }
}
