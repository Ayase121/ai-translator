package com.example.aitranslator.domain;

import java.util.Arrays;

public enum TranslationStyle {
    NATURAL("natural", "自然"),
    FAITHFUL("faithful", "忠实"),
    FORMAL("formal", "正式"),
    CONCISE("concise", "简洁");

    private final String code;
    private final String displayName;

    TranslationStyle(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public static TranslationStyle fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的翻译风格: " + code));
    }
}
