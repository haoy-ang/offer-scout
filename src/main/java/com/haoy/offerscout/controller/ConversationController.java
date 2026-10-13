package com.haoy.offerscout.controller;

import com.haoy.offerscout.chat.ChatMessageVO;
import com.haoy.offerscout.chat.Conversation;
import com.haoy.offerscout.chat.ConversationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话管理接口。
 * <p>
 * 这几个接口的存在，标志着记忆从「进程内变量」变成了「有归属的数据」：
 * 前端可以列出历史会话、点进去看记录、删掉不想要的。
 */
@RestController
@RequestMapping("/ai/conversation")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** 会话列表，按最后活跃时间倒序 */
    @GetMapping("/list")
    public List<Conversation> list() {
        return conversationService.list();
    }

    /** 某个会话的聊天记录 */
    @GetMapping("/{conversationId}/messages")
    public List<ChatMessageVO> messages(@PathVariable String conversationId) {
        return conversationService.getMessages(conversationId).stream()
                .map(ChatMessageVO::of)
                .toList();
    }

    /** 删除会话（元数据与消息一起清） */
    @DeleteMapping("/{conversationId}")
    public boolean delete(@PathVariable String conversationId) {
        return conversationService.delete(conversationId);
    }
}
