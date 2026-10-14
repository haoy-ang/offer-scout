package com.haoy.offerscout.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * 会话元数据（对应 conversation 表）。
 * <p>
 * 用 record：它是只读的数据载体，不需要 setter，也不该被随意改字段。
 */
public record Conversation(
        String id,
        /**
         * 归属用户。
         * <p>
         * {@code @JsonIgnore} 是刻意的：这个字段是"行归属"，属于服务端内部状态，
         * 前端拿它没有任何用处（它一定等于当前登录用户），不该出现在接口契约里。
         * <b>加 @JsonIgnore 只是减少噪音，不是安全手段</b> ——
         * 真正防越权靠的是 SQL 里的 {@code WHERE user_id = ?}，而不是序列化时藏字段。
         * （顺带一提：这个字段历史上叫"数据库列权限控制"，是很多 ORM 项目的经典反例。）
         */
        @JsonIgnore
        Long userId,
        String title,
        int messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
