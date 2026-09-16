package com.example.aitranslator.controller;

import com.example.aitranslator.domain.Language;
import com.example.aitranslator.exception.ApiExceptionHandler;
import com.example.aitranslator.service.DocumentTranslationOptions;
import com.example.aitranslator.service.DocumentTranslationService;
import com.example.aitranslator.service.TranslatedDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.argThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentTranslationControllerTest {

    @Test
    void marksPartiallyTranslatedPdfDownload() throws Exception {
        DocumentTranslationService service = mock(DocumentTranslationService.class);
        when(service.translate(org.mockito.ArgumentMatchers.any(), eq(Language.EN), eq(Language.ZH_CN),
                org.mockito.ArgumentMatchers.any(DocumentTranslationOptions.class)))
                .thenReturn(new TranslatedDocument("paper-translated.docx", "docx".getBytes(), true,
                        120, 94));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentTranslationController(service)).build();

        mvc.perform(multipart("/api/v1/translations/document")
                        .file(new MockMultipartFile("file", "paper.pdf", "application/pdf", "source".getBytes()))
                        .param("sourceLanguage", "en")
                        .param("targetLanguage", "zh-CN"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Translation-Status", "partial"))
                .andExpect(header().string("X-Translation-Coverage", "94/120"));
    }

    @Test
    void passesDocumentStrategyAndProtectedTermsToService() throws Exception {
        DocumentTranslationService service = mock(DocumentTranslationService.class);
        when(service.translate(
                org.mockito.ArgumentMatchers.any(), eq(Language.EN), eq(Language.ZH_CN),
                org.mockito.ArgumentMatchers.any(DocumentTranslationOptions.class)))
                .thenReturn(new TranslatedDocument("guide-translated.docx", "docx".getBytes()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentTranslationController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(multipart("/api/v1/translations/document")
                        .file(new MockMultipartFile("file", "guide.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "source".getBytes()))
                        .param("sourceLanguage", "en")
                        .param("targetLanguage", "zh-CN")
                        .param("documentType", "technical")
                        .param("translationStyle", "formal")
                        .param("protectedTerms", "SDK", "API"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("docx".getBytes()));

        verify(service).translate(
                org.mockito.ArgumentMatchers.any(), eq(Language.EN), eq(Language.ZH_CN),
                argThat(options -> options.documentType().code().equals("technical")
                        && options.translationStyle().code().equals("formal")
                        && options.protectedTerms().equals(List.of("SDK", "API"))));
    }

    @Test
    void usesDefaultDocumentStrategyWhenOptionalParametersAreMissing() throws Exception {
        DocumentTranslationService service = mock(DocumentTranslationService.class);
        when(service.translate(
                org.mockito.ArgumentMatchers.any(), eq(Language.EN), eq(Language.ZH_CN),
                org.mockito.ArgumentMatchers.any(DocumentTranslationOptions.class)))
                .thenReturn(new TranslatedDocument("guide-translated.docx", "docx".getBytes()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentTranslationController(service)).build();

        mvc.perform(multipart("/api/v1/translations/document")
                        .file(new MockMultipartFile("file", "guide.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                "source".getBytes()))
                        .param("sourceLanguage", "en")
                        .param("targetLanguage", "zh-CN"))
                .andExpect(status().isOk());

        verify(service).translate(org.mockito.ArgumentMatchers.any(), eq(Language.EN), eq(Language.ZH_CN),
                argThat(options -> options.documentType().code().equals("general")
                        && options.translationStyle().code().equals("natural")
                        && options.protectedTerms().isEmpty()));
    }
}
