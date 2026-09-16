package com.example.aitranslator.domain;

import java.util.Arrays;

public enum Language {
    AUTO("auto", "自动检测", true),
    ZH_CN("zh-CN", "简体中文", true),
    EN("en", "英语", true),
    JA("ja", "日语", false),
    KO("ko", "韩语", false),
    FR("fr", "法语", false),
    DE("de", "德语", false),
    ES("es", "西班牙语", false),
    RU("ru", "俄语", false),
    PT("pt", "葡萄牙语", false),
    IT("it", "意大利语", false),
    AR("ar", "阿拉伯语", false);

    private final String code;
    private final String displayName;
    private final boolean ocrSupported;

    Language(String code, String displayName, boolean ocrSupported) {
        this.code = code;
        this.displayName = displayName;
        this.ocrSupported = ocrSupported;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public boolean ocrSupported() {
        return ocrSupported;
    }

    public static Language fromCode(String code) {
        return Arrays.stream(values())
                .filter(language -> language.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的语言代码: " + code));
    }
}
