package com.haoy.offerscout.chat;

import java.time.LocalDateTime;

/**
 * 会话元数据（对应 conversation 表）。
 * <p>
 * 用 record：它是只读的数据载体，不需要 setter，也不该被随意改字段。
 */
public record Conversation(
        String id,
        String title,
        int messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
