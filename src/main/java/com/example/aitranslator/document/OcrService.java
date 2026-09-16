package com.example.aitranslator.document;

import java.awt.image.BufferedImage;

@FunctionalInterface
public interface OcrService {

    String recognize(BufferedImage image);
}
