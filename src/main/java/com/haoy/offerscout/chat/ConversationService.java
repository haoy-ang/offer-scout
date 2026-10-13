package com.haoy.offerscout.chat;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话业务逻辑。
 * <p>
 * 把「会话元数据」（自己维护的 conversation 表）和「会话消息」（Spring AI 的
 * JdbcChatMemoryRepository）这两份状态放在一起管理，
 * 上层（JobApp / Controller）只跟本类打交道，不直接碰仓储。
 */
@Service
@Slf4j
public class ConversationService {

    /** 标题最多取多长，超出加省略号 */
    private static final int TITLE_MAX_LENGTH = 30;

    private final ConversationRepository conversationRepository;
    private final ChatMemory chatMemory;

    public ConversationService(ConversationRepository conversationRepository, ChatMemory chatMemory) {
        this.conversationRepository = conversationRepository;
        this.chatMemory = chatMemory;
    }

    /**
     * 确保会话存在（幂等）。
     * <p>
     * 幂等很关键：每轮对话都会调一次，不能每轮都新建一条记录。
     *
     * @param conversationId 会话 ID
     * @param firstMessage   首条用户消息，用于生成标题
     */
    public void ensureExists(String conversationId, String firstMessage) {
        try {
            conversationRepository.insertIfAbsent(conversationId, buildTitle(firstMessage));
        } catch (Exception e) {
            // 元数据写失败不该阻断对话本身：消息照样会存进 SPRING_AI_CHAT_MEMORY，
            // 只是会话列表里看不到它。记日志，让对话继续。
            log.warn("会话元数据写入失败，conversationId={}", conversationId, e);
        }
    }

    /**
     * 用记忆里实际的条数刷新计数。
     * <p>
     * 为什么不「+2」而是回读真实条数？因为记忆是滑动窗口，
     * 条数到上限后不再增长，靠自增会把数字算错。
     */
    public void refreshMessageCount(String conversationId) {
        try {
            conversationRepository.updateMessageCount(conversationId, chatMemory.get(conversationId).size());
        } catch (Exception e) {
            log.warn("会话计数刷新失败，conversationId={}", conversationId, e);
        }
    }

    /** 会话列表（按最后活跃时间倒序） */
    public List<Conversation> list() {
        return conversationRepository.findAllOrderByUpdatedAtDesc();
    }

    /** 读取某个会话的记忆消息 */
    public List<Message> getMessages(String conversationId) {
        return chatMemory.get(conversationId);
    }

    /** 删除会话：元数据 + 消息一起清掉，避免留下孤儿数据 */
    public boolean delete(String conversationId) {
        chatMemory.clear(conversationId);
        return conversationRepository.deleteById(conversationId) > 0;
    }

    /** 拿首条用户消息当标题，压缩空白并截断 */
    private String buildTitle(String message) {
        if (StrUtil.isBlank(message)) {
            return "新会话";
        }
        String title = message.replaceAll("\\s+", " ").trim();
        return title.length() <= TITLE_MAX_LENGTH
                ? title
                : title.substring(0, TITLE_MAX_LENGTH) + "…";
    }
}
