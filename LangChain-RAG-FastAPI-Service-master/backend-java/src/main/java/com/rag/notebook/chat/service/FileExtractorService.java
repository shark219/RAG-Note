package com.rag.notebook.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Slf4j
@Service
public class FileExtractorService {

    private static final int MAX_TEXT_LENGTH = 5000;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".json", ".csv", ".xml", ".yml", ".yaml",
            ".log", ".ini", ".conf", ".py", ".js", ".ts", ".java",
            ".html", ".css", ".sql", ".sh", ".bat", ".properties"
    );

    /**
     * 根据文件类型提取文本内容
     */
    public String extractText(File file, String originalName, String contentType) {
        if (file == null || !file.exists()) return null;

        String ext = getExtension(originalName);

        try {
            String text = null;

            // 纯文本文件
            if (TEXT_EXTENSIONS.contains(ext) || (contentType != null && contentType.startsWith("text/"))) {
                text = readPlainText(file);
            }
            // Word 文档
            else if (".docx".equals(ext)) {
                text = extractDocx(file);
            }
            // PDF 文档
            else if (".pdf".equals(ext)) {
                text = extractPdf(file);
            }

            if (text == null) return null;

            // 清洗：去除多余空行
            text = text.replaceAll("\\n{3,}", "\n\n").trim();

            // 截断
            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH) + "\n...(内容过长已截断)";
            }

            log.info("文件文本提取成功: {}, 长度={}", originalName, text.length());
            return text;

        } catch (Exception e) {
            log.warn("文件文本提取失败: {}, error={}", originalName, e.getMessage());
            return null;
        }
    }

    private String readPlainText(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
                if (sb.length() > MAX_TEXT_LENGTH * 2) break; // 提前终止，避免读过大文件
            }
        }
        return sb.toString();
    }

    private String extractDocx(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }
            return sb.toString();
        }
    }

    private String extractPdf(File file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }
}
