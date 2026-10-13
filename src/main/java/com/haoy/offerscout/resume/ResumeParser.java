package com.haoy.offerscout.resume;

import com.haoy.offerscout.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 简历 PDF 解析器：把上传的 PDF 变成纯文本。
 * <p>
 * 底层是 Spring AI 的 {@link PagePdfDocumentReader}（内部用 Apache PdfBox）。
 * <b>为什么落盘成临时文件而不是直接把流喂给 reader？</b>
 * PdfBox 解析时可能多次随机访问字节流，直接传 {@code MultipartFile} 的 InputStream
 * 在"不能重复读 / 不支持 seek"时会有坑；落盘后用 {@link FileSystemResource} 最稳。
 * <p>
 * <b>一个必须提前告诉用户的局限</b>：PdfBox 提取的是 PDF 的**文本层**。
 * 如果这份 PDF 是扫描件 / 图片导出（没有文本层），提取出来就是空的 ——
 * 这类"图片型 PDF"只能上 OCR，本项目不做，遇到时给明确提示，而不是返回一段空诊断。
 * 所以接口层同时接受「直接粘贴文本」，作为这条路的兜底。
 */
@Component
@Slf4j
public class ResumeParser {

    /** 提取出的文本小于这个长度，基本可以判定是图片型 PDF 或空文件 */
    private static final int MIN_MEANINGFUL_CHARS = 30;

    /** 简历文本太长就截断 —— 防止有人传一本 300 页的书把上下文撑爆 */
    private static final int MAX_CHARS = 15000;

    /**
     * 解析 PDF 并提取纯文本。
     *
     * @throws BusinessException 文件不是合法 PDF / 提取不出文本（图片型）时抛 400
     */
    public String extractText(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请先选择要上传的简历文件");
        }

        Path temp = null;
        try {
            // 落盘到系统临时目录，解析完即删
            temp = Files.createTempFile("offerscout-resume-", ".pdf");
            file.transferTo(temp.toFile());

            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder().build();
            PagePdfDocumentReader reader = new PagePdfDocumentReader(new FileSystemResource(temp), config);

            List<Document> pages = reader.get();
            String text = pages.stream()
                    .map(Document::getText)
                    .filter(s -> s != null && !s.isBlank())
                    .reduce("", (a, b) -> a + "\n" + b)
                    .trim();

            if (text.length() < MIN_MEANINGFUL_CHARS) {
                // 不是"解析失败"，是"这份 PDF 里没有文本" —— 两种原因要分清楚告诉用户
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "未能从 PDF 中提取到文字内容。这份文件可能是扫描件或图片型 PDF（没有文本层），"
                                + "请改用文字版 PDF，或直接粘贴简历文本。");
            }
            log.info("简历 PDF 解析成功：{} 页，提取 {} 字符", pages.size(), text.length());

            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("简历 PDF 解析失败", e);
            throw new BusinessException(HttpStatus.BAD_REQUEST, "简历解析失败，请确认上传的是有效的 PDF 文件。");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignore) {
                    // 临时文件删不掉不影响主流程，交给系统临时目录自己清理
                }
            }
        }
    }
}
