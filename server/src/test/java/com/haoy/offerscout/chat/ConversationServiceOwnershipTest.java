package com.haoy.offerscout.chat;

import com.haoy.offerscout.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话归属校验测试 —— 这是整套鉴权里最该被测试覆盖的地方。
 * <p>
 * <b>为什么这段逻辑值得单独写测试？</b>
 * 因为它防的是"越权读写别人的聊天记录"（IDOR），
 * 而这类漏洞**不会让程序报错、不会让测试变红、功能看起来完全正常** ——
 * 只有用户 A 打开了用户 B 的会话时，你才会发现出事。
 * 靠手点页面几乎测不出来，必须用断言把"不该通过的情况必须失败"钉死。
 * <p>
 * 不启动 Spring 上下文：仓储和记忆都是 mock，跑起来毫秒级，也不需要 MySQL / Redis。
 */
class ConversationServiceOwnershipTest {

    private static final long ALICE = 1L;
    private static final long BOB = 2L;
    private static final String CHAT_ID = "conv-001";

    private ConversationRepository repository;
    private ChatMemory chatMemory;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
        chatMemory = mock(ChatMemory.class);
        service = new ConversationService(repository, chatMemory);
    }

    private Conversation ownedBy(long userId) {
        return new Conversation(CHAT_ID, userId, "标题", 2,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /* ---------------- ensureExists：写入口 ---------------- */

    @Test
    @DisplayName("ensureExists：新建成功时，把当前用户写成会话归属")
    void ensureExists_newConversation_recordsOwner() {
        when(repository.insertIfAbsent(anyString(), anyString(), anyLong())).thenReturn(1);

        service.ensureExists(CHAT_ID, "帮我看看简历", ALICE);

        ArgumentCaptor<Long> owner = ArgumentCaptor.forClass(Long.class);
        verify(repository).insertIfAbsent(eq(CHAT_ID), anyString(), owner.capture());
        // 用 longValue() 取值：assertEquals(long, Long) 会因为自动装箱/拆箱产生歧义重载
        assertEquals(ALICE, owner.getValue().longValue(), "新建的会话必须带上当前用户，否则就是一条无主数据");

        // 新建成功归属已确定，不该再多查一次
        verify(repository, never()).findById(anyString());
    }

    @Test
    @DisplayName("ensureExists：会话已存在且属于自己 → 放行（这是正常的多轮对话）")
    void ensureExists_existingOwnConversation_passes() {
        when(repository.insertIfAbsent(anyString(), anyString(), anyLong())).thenReturn(0);
        when(repository.findById(CHAT_ID)).thenReturn(Optional.of(ownedBy(ALICE)));

        service.ensureExists(CHAT_ID, "第二轮提问", ALICE);

        verify(repository).findById(CHAT_ID);
    }

    @Test
    @DisplayName("ensureExists：拿到了别人的会话 ID → 403（否则消息会写进别人的会话记忆里）")
    void ensureExists_someoneElsesConversation_forbidden() {
        when(repository.insertIfAbsent(anyString(), anyString(), anyLong())).thenReturn(0);
        when(repository.findById(CHAT_ID)).thenReturn(Optional.of(ownedBy(BOB)));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.ensureExists(CHAT_ID, "插一句话", ALICE));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    @Test
    @DisplayName("ensureExists：元数据写库失败时降级放行，不能把正常对话打断")
    void ensureExists_repositoryFailure_degradesGracefully() {
        when(repository.insertIfAbsent(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("数据库连接闪断"));

        // 不抛异常就算通过：消息仍会写进 SPRING_AI_CHAT_MEMORY，只是会话列表里看不到
        service.ensureExists(CHAT_ID, "你好", ALICE);
        verify(repository, never()).findById(anyString());
    }

    /* ---------------- getMessages：读出口 ---------------- */

    @Test
    @DisplayName("getMessages：别人的会话 → 403，读不到任何消息")
    void getMessages_someoneElsesConversation_forbidden() {
        when(repository.findById(CHAT_ID)).thenReturn(Optional.of(ownedBy(BOB)));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getMessages(CHAT_ID, ALICE));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());

        // 关键断言：既然被拒绝了，就不该再去碰记忆 —— 一次都不行
        verify(chatMemory, never()).get(anyString());
    }

    @Test
    @DisplayName("getMessages：会话不存在 → 404")
    void getMessages_missingConversation_notFound() {
        when(repository.findById(CHAT_ID)).thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getMessages(CHAT_ID, ALICE));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        verify(chatMemory, never()).get(anyString());
    }

    @Test
    @DisplayName("getMessages：自己的会话 → 正常返回消息")
    void getMessages_ownConversation_returnsMessages() {
        when(repository.findById(CHAT_ID)).thenReturn(Optional.of(ownedBy(ALICE)));
        List<Message> stored = List.of(new AssistantMessage("收到"));
        when(chatMemory.get(CHAT_ID)).thenReturn(stored);

        assertEquals(stored, service.getMessages(CHAT_ID, ALICE));
    }

    /* ---------------- list：只能看见自己的 ---------------- */

    @Test
    @DisplayName("list：按当前用户过滤，且不带出别人的会话")
    void list_scopedByUser() {
        when(repository.findByUserOrderByUpdatedAtDesc(ALICE)).thenReturn(List.of(ownedBy(ALICE)));

        List<Conversation> result = service.list(ALICE);

        assertEquals(1, result.size());
        assertEquals(ALICE, result.get(0).userId().longValue());
        // 确认查的是"按用户查"的新方法，而不是那个不带条件的旧查询
        verify(repository).findByUserOrderByUpdatedAtDesc(ALICE);
    }

    /* ---------------- delete：幂等 + 越权拦截 ---------------- */

    @Test
    @DisplayName("delete：删自己的会话 → 返回 true 并清掉记忆")
    void delete_ownConversation_succeeds() {
        when(repository.findById(CHAT_ID)).thenReturn(Optional.of(ownedBy(ALICE)));
        when(repository.deleteByIdAndUserId(CHAT_ID, ALICE)).thenReturn(1);

        assertTrue(service.delete(CHAT_ID, ALICE));
        verify(chatMemory).clear(CHAT_ID);
    }

    @Test
    @DisplayName("delete：删别人的会话 → 403，且不执行任何删除")
    void delete_someoneElsesConversation_forbidden() {
        when(repository.findById(CHAT_ID)).thenReturn(Optional.of(ownedBy(BOB)));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.delete(CHAT_ID, ALICE));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());

        verify(repository, never()).deleteByIdAndUserId(anyString(), anyLong());
        verify(chatMemory, never()).clear(anyString());
    }

    @Test
    @DisplayName("delete：会话本就不存在 → 返回 false 而不是报 404（DELETE 要幂等）")
    void delete_missingConversation_isIdempotent() {
        when(repository.findById(CHAT_ID)).thenReturn(Optional.empty());
        when(repository.deleteByIdAndUserId(CHAT_ID, ALICE)).thenReturn(0);

        assertFalse(service.delete(CHAT_ID, ALICE));
    }

    /* ---------------- refreshMessageCount：不该被归属校验挡住 ---------------- */

    @Test
    @DisplayName("refreshMessageCount：回读真实条数写库，异常时静默降级")
    void refreshMessageCount_updatesCountAndSwallowsFailure() {
        when(chatMemory.get(CHAT_ID)).thenReturn(List.of(new AssistantMessage("a"), new AssistantMessage("b")));

        service.refreshMessageCount(CHAT_ID);
        verify(repository).updateMessageCount(CHAT_ID, 2);

        // 仓储挂了也不该往外抛：它发生在一次已经成功的对话之后，
        // 因为"计数没刷上"而给用户报错，是典型的捡了芝麻丢西瓜。
        // 注意这里必须用 doThrow 而不是 when(...).thenThrow(...)：
        // updateMessageCount 返回 void，而 when() 的入参要求是一个表达式值。
        doThrow(new RuntimeException("deadlock"))
                .when(repository).updateMessageCount(anyString(), anyInt());
        service.refreshMessageCount(CHAT_ID);
    }
}
