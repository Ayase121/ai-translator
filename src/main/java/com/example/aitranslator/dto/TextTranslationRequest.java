package com.example.aitranslator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TextTranslationRequest(
        @NotBlank(message = "翻译文本不能为空")
        @Size(max = 20000, message = "文本不能超过 20000 个字符")
        String text,
        @NotBlank(message = "请选择源语言") String sourceLanguage,
        @NotBlank(message = "请选择目标语言") String targetLanguage) {
}