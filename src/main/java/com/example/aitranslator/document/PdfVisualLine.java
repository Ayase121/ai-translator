package com.example.aitranslator.document;

/** Text-layer geometry used to keep PDF prose in its own column and visual paragraph. */
record PdfVisualLine(String text, float x, float y, float right, float fontSize) {
}
