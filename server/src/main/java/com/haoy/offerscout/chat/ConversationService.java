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
 * 本类管三份状态，它们看着像一回事，其实是三件事：
 * <ul>
 *   <li><b>会话元数据</b>（{@code conversation} 表）：标题、归属、消息数、最后活跃时间。</li>
 *   <li><b>全量归档</b>（{@code chat_message} 表，{@link ChatArchiveRepository}）：
 *       用户看得见的「历史记录」，append-only，永不裁剪。</li>
 *   <li><b>上下文窗口</b>（Spring AI 的 {@code ChatMemory}）：模型这一轮要带进去的历史，
 *       被滑动窗口裁到最近 N 条。</li>
 * </ul>
 * 前两份是"给人看的"，第三份是"给模型看的" —— <b>混用会直接变成产品缺陷</b>：
 * 拿上下文窗口当历史展示，用户聊到第 26 轮就发现前面的全没了。
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
    private final ChatArchiveRepository archiveRepository;
    private final ChatMemory chatMemory;

    public ConversationService(ConversationRepository conversationRepository,
                               ChatArchiveRepository archiveRepository,
                               ChatMemory chatMemory) {
        this.conversationRepository = conversationRepository;
        this.archiveRepository = archiveRepository;
        this.chatMemory = chatMemory;
    }

    /**
     * 归档一轮对话（用户问题 + AI 回答）。
     * <p>
     * <b>这是「归档」而不是「记忆」，两者的裁剪策略相反：</b>
     * 记忆（ChatMemory）会被滑动窗口裁到最近 50 条，供模型当上下文用；
     * 归档（chat_message）永久保留，供归档页回放。
     * 原先只有记忆，所以归档页最多只能显示 50 条 —— 那不是"上下文只带 50 条"的小问题，
     * 是用户看得见的历史被悄悄截断了。
     * <p>
     * <b>失败只记日志，不影响对话</b>：归档是附加能力，
     * 它挂掉时对话本身仍然成功（模型已经答完了）。把归档失败抛出去，
     * 用户会看到"回答失败"却拿不到已经生成的内容，那是更糟的取舍。
     */
    public void archiveTurn(String conversationId, String userText, String assistantText) {
        try {
            archiveRepository.appendTurn(conversationId, userText, assistantText);
        } catch (Exception e) {
            log.warn("对话归档写入失败，conversationId={}", conversationId, e);
        }
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
     * 用归档表里实际的条数刷新计数。
     * <p>
     * 为什么不再读 {@code chatMemory.get(id).size()}？因为那是**窗口内**的条数，
     * 满了就不再增长 —— 会话列表里会显示"50 条消息"，而实际聊了 200 轮。
     * 展示给用户的数字必须来自归档表。
     * <p>
     * 回落到记忆条数是给**上线前的老会话**留的兼容：它们的消息只存在于框架那张表里，
     * 归档表是空的。宁可显示窗口条数，也不要显示 0。
     * <p>
     * 这里不做归属校验：它只在 {@link #ensureExists} 之后、同一次请求内被调用，
     * 归属在那一刻已经确认过了，重复查一次只是多一次无用 SQL。
     */
    public void refreshMessageCount(String conversationId) {
        try {
            int archived = archiveRepository.countByConversationId(conversationId);
            int count = archived > 0 ? archived : chatMemory.get(conversationId).size();
            conversationRepository.updateMessageCount(conversationId, count);
        } catch (Exception e) {
            log.warn("会话计数刷新失败，conversationId={}", conversationId, e);
        }
    }

    /** 某个用户的会话列表（按最后活跃时间倒序） */
    public List<Conversation> list(long userId) {
        return conversationRepository.findByUserOrderByUpdatedAtDesc(userId);
    }

    /**
     * 读取某个会话的**全部**历史消息（归档页用）。
     * <p>
     * 数据源是归档表，不是 {@link ChatMemory} —— 这一点是本方法的核心。
     * 记忆是给模型用的上下文窗口（最多 50 条），拿它当历史展示等于让用户
     * "聊到第 26 轮，前面就没了"。
     * <p>
     * 归档为空时回落到记忆：兼容上线前的老会话（消息只在框架那张表里）。
     * 回落是**一次性**的兼容，不是长期双读 —— 老会话不会再有新消息进来
     * （用户下次说话时那一轮会写进归档，之后就走归档了）。
     *
     * @throws BusinessException 会话不存在（404）或不属于该用户（403）
     */
    public List<Message> getMessages(String conversationId, long userId) {
        requireOwned(conversationId, userId);
        List<Message> archived = archiveRepository.findByConversationId(conversationId);
        return archived.isEmpty() ? chatMemory.get(conversationId) : archived;
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
        // 两处都要清，缺一不可：
        // · 只清归档 → 会话列表没了，但模型下一轮还记得刚才聊了什么（灵异上下文）
        // · 只清记忆 → 归档页点进去还能看到"已删除"的对话
        int deleted = conversationRepository.deleteByIdAndUserId(conversationId, userId);
        archiveRepository.deleteByConversationId(conversationId);
        chatMemory.clear(conversationId);
        return deleted > 0;
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
