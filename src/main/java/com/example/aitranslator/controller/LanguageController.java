package com.example.aitranslator.controller;

import com.example.aitranslator.dto.LanguageResponse;
import com.example.aitranslator.service.LanguageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/languages")
public class LanguageController {

    private final LanguageService languageService;


    @GetMapping
    public List<LanguageResponse> list() {
        return languageService.list();
    }
}