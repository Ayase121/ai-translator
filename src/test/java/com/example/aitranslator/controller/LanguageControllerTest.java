package com.example.aitranslator.controller;

import com.example.aitranslator.dto.LanguageResponse;
import com.example.aitranslator.service.LanguageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LanguageControllerTest {

    @Test
    void delegatesLanguageListToService() throws Exception {
        LanguageService service = mock(LanguageService.class);
        when(service.list()).thenReturn(List.of(
                new LanguageResponse("auto", "自动检测", true, true),
                new LanguageResponse("en", "英语", false, true)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LanguageController(service)).build();

        mvc.perform(get("/api/v1/languages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("auto"))
                .andExpect(jsonPath("$[1].ocrSupported").value(true));
    }
}