package com.haoy.offerscout.chat;

import org.springframework.ai.chat.messages.Message;

/**
 * 对外暴露的消息视图对象。
 * <p>
 * 为什么不直接把 Spring AI 的 {@code Message} 返给前端？
 * 那是框架的领域模型，字段结构可能随版本变化，而且内部元数据不该泄漏到接口层。
 * 定一个自己的 VO，接口契约就稳了。
 */
public record ChatMessageVO(String role, String content) {

    public static ChatMessageVO of(Message message) {
        return new ChatMessageVO(message.getMessageType().getValue(), message.getText());
    }
}
