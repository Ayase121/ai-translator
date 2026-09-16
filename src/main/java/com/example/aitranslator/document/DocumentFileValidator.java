package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

@Component
public class DocumentFileValidator {

    private final long maxBytes;

    public DocumentFileValidator(@Value("${app.document.max-bytes:10485760}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("请选择需要翻译的文件");
        }
        if (file.getSize() > maxBytes) {
            throw new InvalidDocumentException("文件大小不能超过 10 MB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.contains(".")) {
            throw unsupported();
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        byte[] prefix;
        try {
            prefix = file.getBytes();
        } catch (Exception exception) {
            throw new InvalidDocumentException("无法读取上传文件", exception);
        }
        switch (extension) {
            case "txt" -> { }
            case "pdf" -> requireSignature(prefix, new byte[]{'%', 'P', 'D', 'F'}, "PDF 文件签名无效");
            case "docx" -> requireSignature(prefix, new byte[]{'P', 'K'}, "DOCX 文件签名无效");
            default -> throw unsupported();
        }
        return extension;
    }

    private void requireSignature(byte[] bytes, byte[] expected, String message) {
        if (bytes.length < expected.length) {
            throw new InvalidDocumentException(message);
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[index] != expected[index]) {
                throw new InvalidDocumentException(message);
            }
        }
    }

    private InvalidDocumentException unsupported() {
        return new InvalidDocumentException("仅支持 TXT、DOCX、PDF 文件");
    }
}
