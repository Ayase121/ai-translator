package com.example.aitranslator.controller;

import com.example.aitranslator.domain.Language;
import com.example.aitranslator.exception.ApiExceptionHandler;
import com.example.aitranslator.service.TranslationResult;
import com.example.aitranslator.service.TranslationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TranslationControllerTest {

    @Test
    void returnsTranslatedText() throws Exception {
        TranslationService service = mock(TranslationService.class);
        when(service.translateText("你好", Language.ZH_CN, Language.EN))
                .thenReturn(new TranslationResult("Hello", Language.ZH_CN, Language.EN));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TranslationController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/translations/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"你好","sourceLanguage":"zh-CN","targetLanguage":"en"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translatedText").value("Hello"))
                .andExpect(jsonPath("$.sourceLanguage").value("zh-CN"))
                .andExpect(jsonPath("$.targetLanguage").value("en"));
    }

    @Test
    void rejectsBlankTextWithProblemDetail() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TranslationController(mock(TranslationService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/translations/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":" ","sourceLanguage":"auto","targetLanguage":"en"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数错误"));
    }
}