package com.haoy.offerscout.chat;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话全量归档仓储（append-only）。
 * <p>
 * <b>它和 Spring AI 的 {@code JdbcChatMemoryRepository} 是什么关系？</b>
 * 两者存的是同一批对话，但服务的目的完全不同：
 * <ul>
 *   <li>框架那张表（{@code SPRING_AI_CHAT_MEMORY}）是**上下文**：模型这一轮要看到的历史。
 *       它被 {@code MessageWindowChatMemory} 裁剪，只留最近 N 条 —— 这是刻意的，历史全塞进去既贵又没用。</li>
 *   <li>本表是**归档**：产品要展示给用户看的「历史记录」。它必须全量，一轮都不能少。</li>
 * </ul>
 * 原先这两件事被混在同一张表上，结果是"上下文只带 50 条"被悄悄放大成"数据库里也只有 50 条"，
 * 归档页滚到一半就没了 —— 产品诉求和实现直接冲突。
 * <p>
 * <b>为什么按「轮」而不是按「条」写入？</b>一轮 = 用户问题 + AI 回答，是一次完整的交互。
 * 放在同一个方法里，语义上"这一轮发生了什么"是原子的：不会出现"问题存进去了、回答丢了"
 * 这种让归档页显示半截对话的状态。至于落库仍是两条记录，因为展示层要按条渲染。
 * <p>
 * <b>为什么不加唯一约束去重？</b>同一句话在同一会话里合法地可能出现两次（用户重复提问）；
 * 用 (conversation_id, content) 去重会把正常对话吞掉。重复写入的防护放在调用侧 ——
 * 每轮只在模型真正返回之后写一次。
 */
@Repository
public class ChatArchiveRepository {

    /** 归档表里只认这三种角色；TOOL 之类属于模型内部消息，不该出现在用户可见的历史里 */
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";
    private static final String ROLE_SYSTEM = "SYSTEM";

    /**
     * 行 → Spring AI 的 {@link Message}。
     * <p>
     * 转回框架的消息类型而不是自定义 DTO，是为了让上层（{@link ConversationService#getMessages}）
     * 的返回类型保持不变 —— 控制器那边的 {@code ChatMessageVO.of(Message)} 一行都不用改。
     */
    private static final RowMapper<Message> ROW_MAPPER = (rs, rowNum) -> {
        String role = rs.getString("role");
        String content = rs.getString("content");
        return switch (role == null ? "" : role) {
            case ROLE_ASSISTANT -> new AssistantMessage(content);
            case ROLE_SYSTEM -> new SystemMessage(content);
            default -> new UserMessage(content);
        };
    };

    private final JdbcTemplate jdbcTemplate;

    public ChatArchiveRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 归档一轮对话。
     * <p>
     * 空白内容直接跳过：模型偶尔会返回 null（比如只吐了工具调用、没有正文），
     * 把空串写进去会让前端渲染出一个空气泡。
     *
     * @param conversationId 会话 ID
     * @param userText       用户这一轮说的话
     * @param assistantText  AI 这一轮的回答
     */
    public void appendTurn(String conversationId, String userText, String assistantText) {
        List<Object[]> rows = new ArrayList<>(2);
        if (userText != null && !userText.isBlank()) {
            rows.add(new Object[]{conversationId, ROLE_USER, userText});
        }
        if (assistantText != null && !assistantText.isBlank()) {
            rows.add(new Object[]{conversationId, ROLE_ASSISTANT, assistantText});
        }
        if (rows.isEmpty()) {
            return;
        }
        // batchUpdate：两条 INSERT 合成一次往返。
        // 更严格的做法是显式事务（问题与回答要么都写、要么都不写），
        // 但归档不是强一致业务：丢一条的后果只是归档页少一句，不影响对话本身，
        // 而为它套一层事务会把"归档失败"升级成"对话失败"。取舍就在这儿。
        jdbcTemplate.batchUpdate(
                "INSERT INTO chat_message (conversation_id, role, content) VALUES (?, ?, ?)", rows);
    }

    /** 按写入顺序取某会话的全部归档消息（全量，不裁剪） */
    public List<Message> findByConversationId(String conversationId) {
        return jdbcTemplate.query(
                "SELECT role, content FROM chat_message WHERE conversation_id = ? ORDER BY id",
                ROW_MAPPER, conversationId);
    }

    /** 某会话归档了多少条 —— 会话列表里的「消息数」用这个，不再用上下文窗口的条数 */
    public int countByConversationId(String conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?", Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    /**
     * 删除某会话的全部归档。
     * <p>
     * 按 conversation_id 一把删：归档表没有外键，所以必须显式清，否则删了会话留下孤儿消息，
     * 将来会话 ID 复用时（理论上）会把别人的历史串进来。
     */
    public int deleteByConversationId(String conversationId) {
        return jdbcTemplate.update(
                "DELETE FROM chat_message WHERE conversation_id = ?", conversationId);
    }
}
