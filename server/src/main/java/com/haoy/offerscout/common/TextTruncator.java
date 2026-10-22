package com.haoy.offerscout.common;

/**
 * 文本语义友好截断工具。
 * <p>
 * 解决硬字符截断（如直接 substring(0, 800)）把关键结论句、句子主干腰斩的问题。
 * 策略：在目标字符预算（maxChars）附近，优先回溯寻找句子终结符（句号、感叹号、问号、分号、换行），
 * 在完整句子边界截断；若回溯窗口内未找到句子边界，再降级为硬截断。
 */
public final class TextTruncator {

    private TextTruncator() {
    }

    /**
     * 在指定字符预算内优雅截断文本。
     *
     * @param text     原始文本
     * @param maxChars 最大字符上限
     * @param suffix   截断后缀（如 "…（已截断）" 或 "..."），传 null 则不拼接
     * @return 截断后的文本
     */
    public static String truncateGracefully(String text, int maxChars, String suffix) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 0) {
            return "";
        }

        // 回溯窗口大小：最多向后回溯 150 个字符寻找句尾，且不能越过文本起始
        int lookbackWindow = Math.min(150, maxChars);
        int minIndex = Math.max(0, maxChars - lookbackWindow);
        int cutoff = maxChars;

        for (int i = maxChars - 1; i >= minIndex; i--) {
            char ch = text.charAt(i);
            if (isSentenceBoundary(ch)) {
                cutoff = i + 1;
                break;
            }
        }

        String truncated = text.substring(0, cutoff).trim();
        return suffix != null ? truncated + suffix : truncated;
    }

    private static boolean isSentenceBoundary(char ch) {
        return ch == '。' || ch == '！' || ch == '？' || ch == '\n' || ch == '；'
                || ch == '.' || ch == '!' || ch == '?' || ch == ';';
    }
}
