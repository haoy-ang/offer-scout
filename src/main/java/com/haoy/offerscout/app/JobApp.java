package com.haoy.offerscout.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 求职助手应用 —— 把大模型包装成「Offer 侦察兵」。
 * <p>
 * 这是应用层：负责系统提示词、对话记忆和调用方式，
 * 业务能力（简历诊断、岗位匹配、模拟面试）后续会逐步加到这个类里。
 */
@Component
@Slf4j
public class JobApp {

    private final ChatClient chatClient;

    /**
     * 系统提示词：决定这个 AI 是什么角色、怎么工作。
     * 写提示词的核心不是"夸自己"，而是把工作方式约束清楚。
     */
    private static final String SYSTEM_PROMPT = """
            你是「Offer 侦察兵」，一位专攻互联网技术岗的求职顾问。

            ## 你擅长的领域
            - 技术岗招聘全流程：简历筛选、笔试、技术面、HR 面、Offer 沟通
            - 后端方向（Java / Spring Boot / 数据库 / 分布式）的技能要求与考察重点
            - 简历诊断：项目描述、技术深度、量化表达、与目标岗位的匹配度
            - 面试准备：高频考点、项目追问思路、行为面问题

            ## 你的工作方式
            1. 先问清背景再给建议。不了解用户的目标岗位、当前进度和简历情况时，先追问关键信息，
               不要凭空输出一大段通用建议。
            2. 建议要具体、可执行。不说"多刷题""提升项目深度"这类空话，
               要说"把 XX 项目的并发指标补进简历第二段"这种明确动作。
            3. 说真话。如果发现简历有明显短板、经历与目标岗位不匹配，直接指出来并给出改进路径。
            4. 回答有条理。要点多时分点说明，先给结论再给理由。

            ## 边界
            - 你聚焦技术岗求职，遇到无关话题礼貌地拉回正题。
            - 不编造用户没有的经历，信息不确定时向用户确认。
            """;

    public JobApp(ChatModel dashscopeChatModel) {
        // 基于内存的对话记忆：保留最近 20 条消息，超出后自动丢弃最早的
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();

        this.chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                // 对话记忆靠 Advisor 实现：它会在每次请求前后自动读写历史消息
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 同步对话（支持多轮记忆）。
     *
     * @param message 用户输入
     * @param chatId  会话 ID，同一个 ID 共享上下文
     * @return AI 的完整回复
     */
    public String doChat(String message, String chatId) {
        ChatResponse response = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();

        String content = (response == null || response.getResult() == null)
                ? null
                : response.getResult().getOutput().getText();
        log.info("chatId={} 用户: {} | 回复: {}", chatId, message, content);
        return content;
    }

    /**
     * 流式对话（SSE），适合前端逐字显示。
     *
     * @param message 用户输入
     * @param chatId  会话 ID
     * @return 逐段返回的文本流
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }
}
