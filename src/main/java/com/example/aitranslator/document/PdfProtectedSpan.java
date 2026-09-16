package com.example.aitranslator.document;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source expressions that must remain on the original PDF page rather than be reconstructed. */
public final class PdfProtectedSpan {
    private static final Pattern PATTERN = Pattern.compile(
            "(?i:https?://\\S+)|[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}"
                    + "|(?<!\\w)[A-Za-z][A-Za-z0-9_]*\\s*(?:=|≈|≤|≥|∈)\\s*[A-Za-z0-9_()+*/^.-]+"
                    + "|(?<!\\w)[α-ωΑ-Ω∑∫∞](?!\\w)");

    private PdfProtectedSpan() {
    }

    public static boolean contains(String source) {
        return PATTERN.matcher(source).find();
    }

    public static Matcher matcher(String source) {
        return PATTERN.matcher(source);
    }
}
