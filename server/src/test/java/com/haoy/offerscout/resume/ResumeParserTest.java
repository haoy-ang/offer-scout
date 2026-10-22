package com.haoy.offerscout.resume;

import com.haoy.offerscout.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeParserTest {

    private final ResumeParser parser = new ResumeParser();

    @Test
    @DisplayName("上传空文件或未选文件时抛出 400 业务异常")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> parser.extractText(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先选择要上传的简历文件");

        MockMultipartFile emptyFile = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> parser.extractText(emptyFile))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先选择要上传的简历文件");
    }

    @Test
    @DisplayName("上传非 PDF 文件或损坏文件时捕获并抛出友好业务异常")
    void rejectsInvalidPdfContent() {
        MockMultipartFile corruptFile = new MockMultipartFile(
                "file", "resume.pdf", "application/pdf", "not a real pdf content".getBytes());
        assertThatThrownBy(() -> parser.extractText(corruptFile))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("简历解析失败");
    }
}
