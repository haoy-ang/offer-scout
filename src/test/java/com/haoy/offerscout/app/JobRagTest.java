package com.haoy.offerscout.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/**
 * RAG 集成测试。
 * <p>
 * 注意：会真实调用 Embedding + 对话接口，消耗少量额度。
 * 打了 {@code requires-ai} 标签 —— CI 默认排除，不在流水线里花额度。
 */
@Tag("requires-ai")
@SpringBootTest
class JobRagTest {

    /** 固定测试用户 ID（conversation.user_id 无外键约束，直接约定一个即可） */
    private static final long TEST_USER = 1L;

    @Resource
    private JobApp jobApp;

    @Test
    void testRagChat() {
        String chatId = UUID.randomUUID().toString();

        String answer = jobApp.doChatWithRag(
                "面试官问我 MySQL 的 MVCC 是怎么实现的，我该怎么回答？", chatId, TEST_USER);

        Assertions.assertNotNull(answer, "RAG 回复不应该为空");
        System.out.println("=== RAG 回答 ===\n" + answer);
    }

    @Test
    void testCurrentDateInjected() {
        String answer = jobApp.doChat("现在是哪一年哪个月？只回答年月，不要解释。",
                UUID.randomUUID().toString(), TEST_USER);

        Assertions.assertNotNull(answer);
        System.out.println("=== 日期检查（应为 2026 年 10 月）===\n" + answer);
    }
}
