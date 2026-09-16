package com.example.aitranslator.domain;

import java.util.Arrays;

public enum DocumentType {
    GENERAL("general", "通用"),
    BUSINESS("business", "商务"),
    TECHNICAL("technical", "技术"),
    ACADEMIC("academic", "学术"),
    LEGAL("legal", "法律"),
    MARKETING("marketing", "营销");

    private final String code;
    private final String displayName;

    DocumentType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public static DocumentType fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的文档类型: " + code));
    }
}
