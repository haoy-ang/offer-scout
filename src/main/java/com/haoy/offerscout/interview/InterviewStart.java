package com.haoy.offerscout.interview;

/**
 * 开始面试的返回：会话 ID + 面试官的开场白（含第一个问题）。
 * <p>
 * 前端拿到后要存住 {@code sessionId}，后续每一轮追问和最后的复盘都要带上它。
 */
public record InterviewStart(
        String sessionId,
        String opening
) {
}
