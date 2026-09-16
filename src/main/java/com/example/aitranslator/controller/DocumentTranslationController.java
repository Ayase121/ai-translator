package com.example.aitranslator.controller;

import com.example.aitranslator.domain.Language;
import com.example.aitranslator.domain.DocumentType;
import com.example.aitranslator.domain.TranslationStyle;
import com.example.aitranslator.service.DocumentTranslationService;
import com.example.aitranslator.service.DocumentTranslationOptions;
import com.example.aitranslator.service.TranslatedDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/translations")
public class DocumentTranslationController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private final DocumentTranslationService service;


    @PostMapping(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ByteArrayResource> translate(
            @RequestPart("file") MultipartFile file,
            @RequestParam String sourceLanguage,
            @RequestParam String targetLanguage,
            @RequestParam(defaultValue = "general") String documentType,
            @RequestParam(defaultValue = "natural") String translationStyle,
            @RequestParam(name = "protectedTerms", required = false) List<String> protectedTerms) {
        TranslatedDocument result = service.translate(
                file, Language.fromCode(sourceLanguage), Language.fromCode(targetLanguage),
                new DocumentTranslationOptions(DocumentType.fromCode(documentType),
                        TranslationStyle.fromCode(translationStyle), protectedTerms));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(result.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(DOCX)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Translation-Status", result.partial() ? "partial" : "complete")
                .header("X-Translation-Coverage", result.coveredLines() + "/" + result.eligibleLines())
                .contentLength(result.content().length)
                .body(new ByteArrayResource(result.content()));
    }
}
