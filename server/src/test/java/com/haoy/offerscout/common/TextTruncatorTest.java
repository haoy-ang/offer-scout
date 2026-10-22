package com.haoy.offerscout.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextTruncatorTest {

    @Test
    @DisplayName("短文本不截断")
    void shortTextNotTruncated() {
        String text = "短文本无需截断。";
        assertThat(TextTruncator.truncateGracefully(text, 50, "...")).isEqualTo(text);
    }

    @Test
    @DisplayName("优先在回溯窗口内的句号边界截断，不截断半句话")
    void truncatesAtSentenceBoundary() {
        String sentence1 = "第一句话非常完整。";
        String sentence2 = "第二句话也非常完整。";
        String sentence3 = "第三句话只说了半截还没有说";
        String text = sentence1 + sentence2 + sentence3;

        // 字符预算落在第三句话中间，应该回退到第二句话末尾的句号
        int maxChars = sentence1.length() + sentence2.length() + 5;
        String result = TextTruncator.truncateGracefully(text, maxChars, "…（已截断）");

        assertThat(result)
                .startsWith(sentence1 + sentence2)
                .endsWith("…（已截断）")
                .doesNotContain("第三句话");
    }

    @Test
    @DisplayName("回溯窗口内无句末标点时降级为硬截断")
    void fallbacksToHardCutoffWhenNoSentenceBoundary() {
        String text = "一二三四五六七八九十一二三四五六七八九十";
        String result = TextTruncator.truncateGracefully(text, 10, "...");
        assertThat(result).isEqualTo("一二三四五六七八九十...");
    }

    @Test
    @DisplayName("空值或边界参数安全处理")
    void handlesNullAndZeroGracefully() {
        assertThat(TextTruncator.truncateGracefully(null, 10, "...")).isNull();
        assertThat(TextTruncator.truncateGracefully("测试", 0, "...")).isEmpty();
    }
}
