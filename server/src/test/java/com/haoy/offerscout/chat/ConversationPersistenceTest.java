package com.haoy.offerscout.chat;

import com.haoy.offerscout.app.JobApp;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * <p>
 * <b>为什么归类为 requires-ai，而不是 requires-infra</b>：只有 step2 是"纯读库"，
 * step1 走的是真实链路 {@code jobApp.doChat(...)} —— 它必须真调一次大模型才能产出「助手回复」。
 * 所以整个类要有可用密钥才能跑，CI 默认排除。
 * （若想让它进 CI，得改成不经模型、直接往 {@code ChatMemory} 里塞消息 —— 但那就不再是
 * 「真实对话链路能否落库」的证明了，会丢掉这条测试最想守住的东西。）
 */
@Tag("requires-ai")
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConversationPersistenceTest {

    /** 固定会话 ID，方便用 SQL 直接核对 */
    private static final String CHAT_ID = "persist-check-001";

    /** 埋一个暗号，用来验证内容而不仅仅是条数 */
    private static final String SECRET = "紫水晶七号";

    /** 会话归属：鉴权上线后，写会话必须带上用户 */
    private static final long USER_ID = 1L;

    @Resource
    private JobApp jobApp;

    @Resource
    private ChatMemory chatMemory;

    @Resource
    private ConversationService conversationService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    void step1WriteTurn() {
        // 先清掉上次运行的残留，让本测试可以反复跑。
        // 这里刻意用原始 SQL 而不是 conversationService.delete(CHAT_ID, USER_ID)：
        // 业务方法现在带归属校验，而"鉴权上线之前"这条固定 ID 的记录 user_id 是 NULL，
        // 走业务方法会判定为"别人的会话"而拒绝删除 —— 清理这种历史遗留数据，
        // 正该用 SQL 直连数据库，而不是绕过校验（也绝不能为了测试去掉校验）。
        jdbcTemplate.update("DELETE FROM conversation WHERE id = ?", CHAT_ID);
        // 归档表也要清：它 append-only、不受记忆窗口影响，不清的话每跑一次就多一轮，
        // 归档页会累积出重复对话（这条正是"归档与上下文解耦"之后的连带清理责任）
        jdbcTemplate.update("DELETE FROM chat_message WHERE conversation_id = ?", CHAT_ID);
        chatMemory.clear(CHAT_ID);

        String reply = jobApp.doChat(
                "请记住这个暗号：" + SECRET + "。这一轮只回复两个字：收到", CHAT_ID, USER_ID);

        System.out.println("=== 第 1 步（写入）===");
        System.out.println("AI 回复: " + reply);
        System.out.println("记忆条数: " + chatMemory.get(CHAT_ID).size());
        Assertions.assertTrue(chatMemory.get(CHAT_ID).size() >= 2, "至少应该存下用户消息和助手回复");

        // 归档验证：真实链路跑完一轮，归档表必须落下一问一答两条。
        // 这条断言守住的是"归档写在业务路径上"，不是写在某个只在测试里调用的方法里 ——
        // 少了它，即使 archiveTurn 实现是空的，上面的记忆断言也照样绿。
        Integer archived = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?", Integer.class, CHAT_ID);
        Assertions.assertTrue(archived != null && archived >= 2,
                "一轮对话应该归档两条（用户问题 + 助手回答），实际：" + archived);
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

        // 归档页走的是 getMessages（数据源 = chat_message），和上面直接读 chatMemory 是两条路。
        // 两条都要能读到暗号，才说明"给模型看的"和"给人看的"都没丢。
        List<Message> archived = conversationService.getMessages(CHAT_ID, USER_ID);
        Assertions.assertTrue(
                archived.stream().anyMatch(m -> m.getText() != null && m.getText().contains(SECRET)),
                "归档页（getMessages）也应该能读到暗号内容");

        Optional<Conversation> conversation = conversationService.list(USER_ID).stream()
                .filter(c -> CHAT_ID.equals(c.id()))
                .findFirst();
        Assertions.assertTrue(conversation.isPresent(), "会话元数据也应该还在");
        System.out.println("会话元数据: " + conversation.get());
    }
}
