package com.haoy.offerscout.app;

import com.haoy.offerscout.chat.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.LocalDate;

/**
 * 求职助手应用 —— 把大模型包装成「Offer 侦察兵」。
 * <p>
 * 这是应用层：负责系统提示词、对话记忆和调用方式，
 * 业务能力（简历诊断、岗位匹配、模拟面试）后续会逐步加到这个类里。
 * <p>
 * 对话记忆不再自己 new，而是注入 {@link ChatMemory} Bean ——
 * 底层的存储实现（内存 / MySQL）由配置决定，应用层不关心。
 */
@Component
@Slf4j
public class JobApp {

    private final ChatClient chatClient;

    /** 会话元数据与消息记忆的读写入口 */
    private final ConversationService conversationService;

    /** 用于回读真实消息条数（滑动窗口的条数不能靠自增推算） */
    private final ChatMemory chatMemory;

    /**
     * RAG Advisor：检索增强。
     * 它会在每次请求前拿用户问题去向量库检索相似片段，把命中的资料拼进提示词，
     * 这样模型回答就是「基于你的知识库」而不是「凭记忆瞎编」。
     */
    private final QuestionAnswerAdvisor questionAnswerAdvisor;

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

    public JobApp(ChatModel dashscopeChatModel,
                  SimpleVectorStore jobVectorStore,
                  ChatMemory chatMemory,
                  ConversationService conversationService) {
        this.chatMemory = chatMemory;
        this.conversationService = conversationService;

        // 检索配置：取相似度最高的 4 段；similarityThreshold 为 0 表示不做阈值过滤（先保证能召回）
        this.questionAnswerAdvisor = QuestionAnswerAdvisor.builder(jobVectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(4)
                        .similarityThreshold(0.0)
                        .build())
                .build();

        // 把当天日期写进系统提示词：模型诞生于训练数据截止日，不告诉它今天几号它会瞎猜
        String systemPrompt = SYSTEM_PROMPT
                + "\n## 时间\n今天的日期是 " + LocalDate.now()
                + "。遇到今年、现在、最近这类相对时间，以这个日期为准，不要凭训练数据推断。\n";

        this.chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(systemPrompt)
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
        conversationService.ensureExists(chatId, message);

        ChatResponse response = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();

        String content = (response == null || response.getResult() == null)
                ? null
                : response.getResult().getOutput().getText();
        log.info("chatId={} 用户: {} | 回复: {}", chatId, message, content);

        conversationService.refreshMessageCount(chatId);
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
        conversationService.ensureExists(chatId, message);

        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content()
                // 流是懒执行的：必须等它真正跑完，消息才写入记忆，这时回读条数才有意义
                .doOnComplete(() -> conversationService.refreshMessageCount(chatId));
    }

    /**
     * 带知识库的同步对话（RAG）。
     * <p>
     * 和 {@link #doChat} 的唯一区别是额外挂载了 {@code QuestionAnswerAdvisor}：
     * 它会先用向量检索从知识库里捞出相关片段，再让模型基于这些片段作答。
     *
     * @param message 用户输入
     * @param chatId  会话 ID
     * @return AI 的完整回复
     */
    public String doChatWithRag(String message, String chatId) {
        conversationService.ensureExists(chatId, message);

        ChatResponse response = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec
                        .advisors(questionAnswerAdvisor)
                        .param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();

        String content = (response == null || response.getResult() == null)
                ? null
                : response.getResult().getOutput().getText();
        log.info("chatId={} [RAG] 用户: {} | 回复: {}", chatId, message, content);

        conversationService.refreshMessageCount(chatId);
        return content;
    }

    /** 供测试/接口层查看某会话当前记忆条数 */
    public int getMemorySize(String chatId) {
        return chatMemory.get(chatId).size();
    }

    /**
     * 带知识库的流式对话（RAG + SSE）。
     * <p>
     * 只有同步版的话，前端要干等十几秒才看到整段回答；检索本身很快，
     * 慢的是模型生成，而这部分完全可以边生成边推送。
     */
    public Flux<String> doChatWithRagByStream(String message, String chatId) {
        conversationService.ensureExists(chatId, message);

        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec
                        .advisors(questionAnswerAdvisor)
                        .param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content()
                .doOnComplete(() -> conversationService.refreshMessageCount(chatId));
    }
}
