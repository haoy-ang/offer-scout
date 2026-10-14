package com.haoy.offerscout.chat;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话业务逻辑。
 * <p>
 * 把「会话元数据」（自己维护的 conversation 表）和「会话消息」（Spring AI 的
 * JdbcChatMemoryRepository）这两份状态放在一起管理，
 * 上层（JobApp / Controller）只跟本类打交道，不直接碰仓储。
 * <p>
 * <b>本类也是会话越权校验的唯一入口。</b>原因在于"会话 ID 是前端传上来的"这件事：
 * 只要有人拿到（或猜到）一个别人的会话 ID，就能靠它读写别人的聊天记录 ——
 * 这类漏洞叫 <b>IDOR（不安全的直接对象引用）</b>，是接口层最常见的一类越权。
 * 所以每个按 ID 操作的方法都先确认归属，再把动作落到数据上。
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
     * 确保会话存在，并且属于该用户（幂等）。
     * <p>
     * 幂等很关键：每轮对话都会调一次，不能每轮都新建一条记录。
     * <p>
     * <b>这里藏着一个不显眼但后果很重的坑：</b>
     * {@code INSERT IGNORE} 在"ID 已存在"时静默跳过，不报错也不区分归属。
     * 如果就这么放行，用户 A 传一个用户 B 的会话 ID，A 的消息就会被追加进 B 的会话记忆里 ——
     * 下一轮 B 提问时，模型会把 A 的话当成 B 自己说过的上下文。
     * 所以插入失败（= 已存在）时必须回头确认归属。
     *
     * @param conversationId 会话 ID（前端可传，也可能是新建的 UUID）
     * @param firstMessage   首条用户消息，用于生成标题
     * @param userId         当前登录用户
     * @throws BusinessException 会话已存在且不属于该用户时抛 403
     */
    public void ensureExists(String conversationId, String firstMessage, long userId) {
        int inserted;
        try {
            inserted = conversationRepository.insertIfAbsent(conversationId, buildTitle(firstMessage), userId);
        } catch (Exception e) {
            // 元数据写失败不该阻断对话本身：消息照样会存进 SPRING_AI_CHAT_MEMORY，
            // 只是会话列表里看不到它。记日志，让对话继续。
            log.warn("会话元数据写入失败，conversationId={}", conversationId, e);
            return;
        }
        if (inserted > 0) {
            return; // 新建成功，归属已经确定，无需再查
        }

        // 走到这里说明 ID 已存在 —— 必须确认它属于当前用户。
        // 查不到记录（并发删除）时选择放行：此时写进去的消息只是暂时"无主"，
        // 而误报 403 会直接打断一次正常的对话。
        boolean owned = conversationRepository.findById(conversationId)
                .map(c -> Objects.equals(c.userId(), userId))
                .orElse(true);
        if (!owned) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
    }

    /**
     * 用记忆里实际的条数刷新计数。
     * <p>
     * 为什么不「+2」而是回读真实条数？因为记忆是滑动窗口，
     * 条数到上限后不再增长，靠自增会把数字算错。
     * <p>
     * 这里不做归属校验：它只在 {@link #ensureExists} 之后、同一次请求内被调用，
     * 归属在那一刻已经确认过了，重复查一次只是多一次无用 SQL。
     */
    public void refreshMessageCount(String conversationId) {
        try {
            conversationRepository.updateMessageCount(conversationId, chatMemory.get(conversationId).size());
        } catch (Exception e) {
            log.warn("会话计数刷新失败，conversationId={}", conversationId, e);
        }
    }

    /** 某个用户的会话列表（按最后活跃时间倒序） */
    public List<Conversation> list(long userId) {
        return conversationRepository.findByUserOrderByUpdatedAtDesc(userId);
    }

    /**
     * 读取某个会话的记忆消息。
     *
     * @throws BusinessException 会话不存在（404）或不属于该用户（403）
     */
    public List<Message> getMessages(String conversationId, long userId) {
        requireOwned(conversationId, userId);
        return chatMemory.get(conversationId);
    }

    /**
     * 删除会话：元数据 + 消息一起清掉，避免留下孤儿数据。
     * <p>
     * <b>归属判断在这里和 {@link #getMessages} 不一样，是刻意的：</b>
     * 「会话不存在」不算错误 —— DELETE 在 HTTP 语义里应当**幂等**，
     * 删一个已经不存在的东西，结果和删掉它是同一个状态，报 404 只会制造麻烦
     * （两个标签页同时删、前端重试、测试清理，都会撞上这个 404）。
     * 但「会话存在却不属于你」必须是 403：那是明确的越权尝试，不能悄悄当成功。
     *
     * @return true 表示确实删掉了一条；false 表示本来就不存在（幂等成功）
     * @throws BusinessException 会话存在但不属于该用户（403）
     */
    public boolean delete(String conversationId, long userId) {
        Optional<Conversation> existing = conversationRepository.findById(conversationId);
        if (existing.isPresent() && !Objects.equals(existing.get().userId(), userId)) {
            log.warn("越权删除会话：conversationId={}, owner={}, requester={}",
                    conversationId, existing.get().userId(), userId);
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
        chatMemory.clear(conversationId);
        return conversationRepository.deleteByIdAndUserId(conversationId, userId) > 0;
    }

    /**
     * 归属校验：会话必须存在且属于该用户，否则抛异常。
     * <p>
     * 404 和 403 分开报：对当前用户**自己的**会话体系来说，
     * "你说的这个 ID 根本不存在"和"这个 ID 存在但不是你的"是两种不同的错误，
     * 前端提示也不同。如果更在意"不泄露对象是否存在"，统一报 404 也是常见做法 ——
     * 本项目会话 ID 是 UUID，猜不出来，所以这里选择把话说清楚。
     */
    private void requireOwned(String conversationId, long userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (!Objects.equals(conversation.userId(), userId)) {
            log.warn("越权访问会话：conversationId={}, owner={}, requester={}",
                    conversationId, conversation.userId(), userId);
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
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
