package com.haoy.offerscout.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/**
 * 求职助手应用测试。
 * <p>
 * 注意：这是真实调用大模型的集成测试，会消耗少量额度。
 */
@SpringBootTest
class JobAppTest {

    /**
     * 固定测试用户 ID。
     * <p>
     * conversation 表上的 user_id 没有外键约束（见 schema.sql），
     * 所以这里直接用一个约定值即可，不必真的先注册一个用户。
     */
    private static final long TEST_USER = 1L;

    @Resource
    private JobApp jobApp;

    @Test
    void testMultiTurnChat() {
        String chatId = UUID.randomUUID().toString();

        // 第一轮：抛出一个模糊的需求，看 AI 会不会先追问背景
        String first = jobApp.doChat(
                "你好，我是 2027 届计算机专业的学生，想找 Java 后端的工作", chatId, TEST_USER);
        Assertions.assertNotNull(first, "第一轮回复不应该为空");
        System.out.println("=== 第一轮回复 ===\n" + first);

        // 第二轮：验证对话记忆是否生效
        String second = jobApp.doChat("我上面说我什么时候毕业的？", chatId, TEST_USER);
        Assertions.assertNotNull(second, "第二轮回复不应该为空");
        System.out.println("=== 第二轮回复（记忆验证）===\n" + second);
    }
}
