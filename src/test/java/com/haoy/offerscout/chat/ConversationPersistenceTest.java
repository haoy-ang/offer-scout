package com.haoy.offerscout.chat;

import com.haoy.offerscout.app.JobApp;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

/**
 * 会话持久化验证。
 * <p>
 * 这两个方法要分两次 JVM 运行，才有说服力：
 * <pre>
 *   mvn test -Dtest=ConversationPersistenceTest#step1WriteTurn        # 第一个进程：写入
 *   mvn test -Dtest=ConversationPersistenceTest#step2ReadAfterRestart # 第二个进程：读取
 * </pre>
 * step2 全程不调用大模型 —— 它只是从 MySQL 里把数据读出来。
 * 前一个进程已经退出，能读到内容就说明记忆真的落库了，不是进程内变量。
 * <p>
 * 加了 {@code @Order} 是为了让「一次跑全量测试」时也有确定的先后顺序；
 * 但同一个 JVM 内跑通不算严格证明重启不丢，真正的证据是上面那两条分开执行的命令。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConversationPersistenceTest {

    /** 固定会话 ID，方便用 SQL 直接核对 */
    private static final String CHAT_ID = "persist-check-001";

    /** 埋一个暗号，用来验证内容而不仅仅是条数 */
    private static final String SECRET = "紫水晶七号";

    @Resource
    private JobApp jobApp;

    @Resource
    private ChatMemory chatMemory;

    @Resource
    private ConversationService conversationService;

    @Test
    @Order(1)
    void step1WriteTurn() {
        // 先清掉上次运行的残留，让本测试可以反复跑
        conversationService.delete(CHAT_ID);

        String reply = jobApp.doChat(
                "请记住这个暗号：" + SECRET + "。这一轮只回复两个字：收到", CHAT_ID);

        System.out.println("=== 第 1 步（写入）===");
        System.out.println("AI 回复: " + reply);
        System.out.println("记忆条数: " + chatMemory.get(CHAT_ID).size());
        Assertions.assertTrue(chatMemory.get(CHAT_ID).size() >= 2, "至少应该存下用户消息和助手回复");
    }

    @Test
    @Order(2)
    void step2ReadAfterRestart() {
        System.out.println("=== 第 2 步（新进程，不调用大模型，只读 MySQL）===");

        List<Message> messages = chatMemory.get(CHAT_ID);
        messages.forEach(m -> System.out.println("  [" + m.getMessageType().getValue() + "] "
                + cn.hutool.core.util.StrUtil.maxLength(String.valueOf(m.getText()).replaceAll("\\s+", " "), 60)));

        Assertions.assertFalse(messages.isEmpty(), "前一个进程写入的消息应该还在");
        Assertions.assertTrue(
                messages.stream().anyMatch(m -> m.getText() != null && m.getText().contains(SECRET)),
                "应该能读到上一轮写入的暗号内容：" + SECRET);

        Optional<Conversation> conversation = conversationService.list().stream()
                .filter(c -> CHAT_ID.equals(c.id()))
                .findFirst();
        Assertions.assertTrue(conversation.isPresent(), "会话元数据也应该还在");
        System.out.println("会话元数据: " + conversation.get());
    }
}
