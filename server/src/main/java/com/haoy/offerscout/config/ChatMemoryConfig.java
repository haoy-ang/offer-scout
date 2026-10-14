package com.haoy.offerscout.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话记忆配置。
 * <p>
 * 记忆分两层，各管一段：
 * <ul>
 *   <li><b>仓库（ChatMemoryRepository）</b>：由
 *       {@code spring-ai-starter-model-chat-memory-repository-jdbc} 自动装配成
 *       {@code JdbcChatMemoryRepository}，负责消息的落库与读取。</li>
 *   <li><b>策略（ChatMemory）</b>：也就是本类里这个 Bean，负责「一次发给大模型多少条历史」。</li>
 * </ul>
 * 这样拆的好处是：换存储（MySQL → Redis / PostgreSQL）只动 starter 依赖，
 * 换窗口大小只动这里的 maxMessages，互不影响。
 * <p>
 * <b>注意一个容易误解的点</b>：MessageWindowChatMemory 是滑动窗口，
 * 它保存时会用裁剪后的窗口覆盖仓库里的内容 —— 所以数据库里只保留最近 maxMessages 条，
 * 不是全量聊天记录。要做「完整存档」得换一个不裁剪的 ChatMemory 实现。
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                // 50 条 ≈ 25 轮对话。调太小会丢上下文，调太大费 token，按业务权衡
                .maxMessages(50)
                .build();
    }
}
