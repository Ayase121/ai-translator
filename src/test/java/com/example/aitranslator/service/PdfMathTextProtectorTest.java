package com.example.aitranslator.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfMathTextProtectorTest {

    @Test
    void leavesOrdinaryNumbersAndAcronymsReadableForTheModel() {
        String source = "GPT-4 improves translation on WMT2022 with 25.5 BLEU points.";

        assertThat(PdfMathTextProtector.protect(source).masked()).isEqualTo(source);
    }

    @Test
    void hidesInlineEquationAndRestoresItAtTheSamePosition() {
        String source = "The score uses t1 = t2 for consistency.";
        PdfMathTextProtector.ProtectedText protectedText = PdfMathTextProtector.protect(source);

        assertThat(protectedText.masked()).doesNotContain("t1 = t2");
        assertThat(protectedText.restore("译:" + protectedText.masked())).contains("译:" + source);
    }
}
