package com.haoy.offerscout.app;

import com.haoy.offerscout.chat.ConversationService;
import com.haoy.offerscout.common.TimeContext;
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

/**
 * 求职助手应用 —— 把大模型包装成「Offer 侦察兵」。
 * <p>
 * 这是应用层：负责系统提示词、对话记忆和调用方式，
 * 业务能力（简历诊断、岗位匹配、模拟面试）后续会逐步加到这个类里。
 * <p>
 * 对话记忆不再自己 new，而是注入 {@link ChatMemory} Bean ——
 * 底层的存储实现（内存 / MySQL）由配置决定，应用层不关心。
 * <p>
 * <b>关于 {@code userId} 参数为什么层层透传，而不是在方法里读 ThreadLocal：</b>
 * 当前用户是放在 {@code ThreadLocal} 里的（见 {@code UserContext}），
 * 而本类的方法会返回 {@link Flux} —— 一旦某个调用点把逻辑挪进
 * {@code doOnNext} / {@code doOnComplete} 这类回调，线程就换成了 Reactor 的，
 * 那时 ThreadLocal 里是空的，而且**不会报错，只会静默地把消息写进"无主会话"**。
 * 让身份作为显式参数出现在签名上，数据依赖一眼可见，也不用担心执行线程是谁。
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
            - **具体技术事实只给有依据的**。下面这几类内容，如果参考资料里没有、你也不确定，
              就明说「这一点我这边没有确切依据，建议按你项目的实际情况填」，不要给一个看起来合理的值：
              · 版本号（如「Nacos 2.2」「Seata 1.8」）
              · 依赖坐标与配置项全名（如「feign.client.config.default.connectTimeout」）
              · 百分比、毫秒数这类量化指标（如「命中率从 72% 提升到 99.3%」）
              · 具体报错信息、行号、类名
              理由：用户会拿这些直接写进简历、答面试。**编出来的细节比少给一个细节伤害大得多** ——
              面试官追问一句"这个数怎么测的"，穿帮的是用户，不是我们。
            - 用户问「要写什么指标」时，给**方法**而不是替他**编数字**：
              例如"用 JMeter 压 200 并发，记录 P99 和 QPS，把实测值填进去"。
            """;

    /**
     * ⚠️ 不要为了写测试方便再加一个「少一个参数」的便利构造器。
     * <p>
     * 这里曾经有一个 4 参构造器，内部写死 {@code this(..., 0.0)} —— 而全仓库没有任何调用者。
     * 它是个陷阱：阈值真相只有两处（`application.yml` + 下面 {@code @Value} 的兜底默认值），
     * 多一个构造器就多一个"看起来一样、实际是旧值 0.0"的入口。
     * 将来谁写单测顺手用了它，就会静默测出与线上不一致的行为，而且很难查。
     * <p>
     * 需要换阈值就传参；不想传就用 Spring 注入的那条路径。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public JobApp(ChatModel dashscopeChatModel,
                  SimpleVectorStore jobVectorStore,
                  ChatMemory chatMemory,
                  ConversationService conversationService,
                  @org.springframework.beans.factory.annotation.Value("${offer-scout.rag.similarity-threshold:0.55}")
                  double similarityThreshold) {
        this.chatMemory = chatMemory;
        this.conversationService = conversationService;

        // 检索配置：取相似度最高的 4 段；相似度阈值支持配置覆盖（经由 rag-eval-set 评估集调优）
        this.questionAnswerAdvisor = QuestionAnswerAdvisor.builder(jobVectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(4)
                        .similarityThreshold(similarityThreshold)
                        .build())
                .build();

        // 把当天日期写进系统提示词：模型的"知识"停在训练数据截止日，不告诉它今天几号它会瞎猜。
        // 口径统一由 TimeContext 提供（各页共用，避免有的页漏拼）。
        String systemPrompt = SYSTEM_PROMPT + TimeContext.promptSection();

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
     * @param userId  当前登录用户；用于新建会话时写入归属，以及校验这个 chatId 是不是他的
     * @return AI 的完整回复
     */
    public String doChat(String message, String chatId, long userId) {
        conversationService.ensureExists(chatId, message, userId);

        ChatResponse response = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();

        String content = (response == null || response.getResult() == null)
                ? null
                : response.getResult().getOutput().getText();
        log.info("chatId={} 用户{}: {} | 回复: {}", chatId, userId, message, content);

        // 归档「这一轮」，归档表才是归档页的数据源（上下文窗口会被裁剪到 50 条）
        conversationService.archiveTurn(chatId, message, content);
        conversationService.refreshMessageCount(chatId);
        return content;
    }

    /**
     * 流式对话（SSE），适合前端逐字显示。
     *
     * @param message 用户输入
     * @param chatId  会话 ID
     * @param userId  当前登录用户
     * @return 逐段返回的文本流
     */
    public Flux<String> doChatByStream(String message, String chatId, long userId) {
        // 这一步在**请求线程**上同步执行（方法体先跑完，才把 Flux 交给框架），
        // 所以越权的 403 会正常抛出、被全局异常处理器接住，前端拿到的是一次干净的报错，
        // 而不是一条"流里突然冒出个错误"的 SSE。
        conversationService.ensureExists(chatId, message, userId);

        // 流式接口没有"返回值"可以拿来归档，所以边推边攒：
        // doOnNext 收到的是增量片段，doOnComplete 时拼成完整回答再落归档。
        // Reactor 保证同一个 Flux 的 onNext 是串行的，所以 StringBuilder 不需要加锁。
        StringBuilder answer = new StringBuilder();

        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content()
                .doOnNext(answer::append)
                // 流是懒执行的：必须等它真正跑完，消息才写入记忆，这时回读条数才有意义。
                // 注意这里跑在 Reactor 线程上，所以只用 chatId，不碰 UserContext
                .doOnComplete(() -> {
                    conversationService.archiveTurn(chatId, message, answer.toString());
                    conversationService.refreshMessageCount(chatId);
                });
    }

    /**
     * 带知识库的同步对话（RAG）。
     * <p>
     * 和 {@link #doChat} 的唯一区别是额外挂载了 {@code QuestionAnswerAdvisor}：
     * 它会先用向量检索从知识库里捞出相关片段，再让模型基于这些片段作答。
     *
     * @param message 用户输入
     * @param chatId  会话 ID
     * @param userId  当前登录用户
     * @return AI 的完整回复
     */
    public String doChatWithRag(String message, String chatId, long userId) {
        conversationService.ensureExists(chatId, message, userId);

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
        log.info("chatId={} [RAG] 用户{}: {} | 回复: {}", chatId, userId, message, content);

        conversationService.archiveTurn(chatId, message, content);
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
    public Flux<String> doChatWithRagByStream(String message, String chatId, long userId) {
        conversationService.ensureExists(chatId, message, userId);

        // 同 doChatByStream：流式回答靠边推边攒，完成时才归档
        StringBuilder answer = new StringBuilder();

        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec
                        .advisors(questionAnswerAdvisor)
                        .param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content()
                .doOnNext(answer::append)
                .doOnComplete(() -> {
                    conversationService.archiveTurn(chatId, message, answer.toString());
                    conversationService.refreshMessageCount(chatId);
                });
    }
}
