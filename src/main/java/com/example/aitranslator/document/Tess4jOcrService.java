package com.example.aitranslator.document;

import com.example.aitranslator.exception.InvalidDocumentException;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

public class Tess4jOcrService implements OcrService {
    private static final Logger log = LoggerFactory.getLogger(Tess4jOcrService.class);

    private final Path dataPath;

    public Tess4jOcrService(String dataPath) {
        this.dataPath = dataPath == null || dataPath.isBlank() ? null : Path.of(dataPath);
    }

    @Override
    public String recognize(BufferedImage image) {
        ensureModelsAvailable();
        if (image == null) {
            throw new InvalidDocumentException("OCR 输入图像为空");
        }
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(dataPath.toString());
        tesseract.setLanguage("chi_sim+eng");
        tesseract.setPageSegMode(3);
        try {
            String result = tesseract.doOCR(image);
            log.info("OCR completed (width={}, height={}, characters={})",
                    image.getWidth(), image.getHeight(), result == null ? 0 : result.length());
            return result;
        } catch (TesseractException exception) {
            log.warn("OCR engine failed (width={}, height={})", image.getWidth(), image.getHeight());
            throw new InvalidDocumentException("OCR 识别失败，请检查扫描质量或 Tesseract 配置", exception);
        }
    }

    private void ensureModelsAvailable() {
        if (dataPath == null || !Files.isRegularFile(dataPath.resolve("chi_sim.traineddata"))
                || !Files.isRegularFile(dataPath.resolve("eng.traineddata"))) {
            throw new InvalidDocumentException("OCR 未配置：TESSDATA_PREFIX 必须包含 chi_sim.traineddata 和 eng.traineddata");
        }
    }
}
