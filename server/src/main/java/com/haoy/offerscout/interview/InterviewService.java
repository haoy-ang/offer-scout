package com.haoy.offerscout.interview;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.common.BusinessException;
import com.haoy.offerscout.common.StructuredOutputRetry;
import com.haoy.offerscout.common.TimeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * 模拟面试业务逻辑：AI 扮演面试官，多轮追问，最后给一场结构化复盘。
 * <p>
 * 这是本项目里**第一个真正的多轮交互功能**（简历诊断、岗位匹配都是一次性调用）。
 * 它靠 Spring AI 的 {@link ChatMemory} 记住整场对话——同一个 sessionId 的每一轮
 * 都会把历史消息带进上下文，所以面试官能"记得"前面问过什么、候选人答过什么。
 * <p>
 * <b>两个 ChatClient 分工</b>：
 * <ul>
 *   <li>{@code interviewClient}：挂了记忆 Advisor，负责开场和每一轮追问（多轮累积）。</li>
 *   <li>{@code reviewClient}：**裸的**，负责最后的复盘。复盘输入是"整场记录"本身，
 *       不需要也不该再叠加记忆（否则历史会被重复喂一遍）。</li>
 * </ul>
 * <p>
 * <b>面试官人设为什么不能做成 defaultSystem？</b>因为它是**动态的**：依赖岗位方向
 * 和面试类型（技术面 / HR 面 / 综合面）。所以每次请求现场拼 system prompt。
 */
@Service
@Slf4j
public class InterviewService {

    /** 会话 ID 前缀：把面试会话和普通对话在记忆表里区分开，同时承载归属信息 */
    private static final String SESSION_PREFIX = "itv-";

    /** 复盘时最多回放多少字符的面试记录，防止超长面试把上下文撑爆 */
    private static final int MAX_TRANSCRIPT_CHARS = 20000;

    /**
     * 开场的「提示语」。它是以 USER 身份发出去的，所以会被 ChatMemory 记下来 ——
     * 但拼复盘记录时必须把它排除，否则会被当成「候选人说的第一句话」污染评估。
     */
    private static final String KICKOFF_CUE = "（面试开始。请简短寒暄一句，然后只提出第一个问题，不要一次问多个。）";

    private final ChatClient interviewClient;
    private final ChatClient reviewClient;
    private final ChatMemory chatMemory;

    public InterviewService(ChatModel chatModel, ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        // 面试对话：挂记忆 Advisor，让面试官记得整场对话
        this.interviewClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        // 复盘：裸 Client，输入是整场记录，不需要记忆
        this.reviewClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 开始一场面试：生成会话 ID，让面试官说开场白 + 第一个问题。
     *
     * @param role   目标岗位（如「Java 后端开发实习生」）
     * @param type   面试类型：tech / hr / mixed
     * @param userId 当前登录用户——会被编进 sessionId，用来防越权
     */
    public InterviewStart start(String role, String type, long userId) {
        // 会话 ID 绑定 userId：别的用户即使拿到这个 ID，前缀对不上也进不来
        String sessionId = SESSION_PREFIX + userId + "-" + UUID.randomUUID();
        String prompt = buildInterviewerPrompt(role, type);

        try {
            ChatResponse response = interviewClient.prompt()
                    .system(prompt)
                    .user(KICKOFF_CUE)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .chatResponse();
            String opening = extractText(response);
            log.info("面试开始：session={} 岗位={} 类型={}", sessionId, role, type);
            return new InterviewStart(sessionId, opening);
        } catch (Exception e) {
            log.error("面试开场生成失败", e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "面试开始失败，请稍后重试。");
        }
    }

    /**
     * 候选人回答一轮，面试官追问（流式）。
     * <p>
     * 归属校验在方法体里**同步**执行（返回 Flux 之前），所以越权会正常抛 403，
     * 而不是在 SSE 流里冒出一个半截错误。
     */
    public Flux<String> chatByStream(String sessionId, String message, String role, String type, long userId) {
        requireOwnedSession(sessionId, userId);
        if (StrUtil.isBlank(message)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "回答内容不能为空。");
        }
        String prompt = buildInterviewerPrompt(role, type);
        return interviewClient.prompt()
                .system(prompt)
                .user(message.trim())
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }

    /**
     * 生成复盘报告：把整场面试记录喂给模型，输出结构化评估。
     *
     * @param role 目标岗位（写进复盘 prompt）
     * @param type 面试类型
     */
    public InterviewReport report(String sessionId, String role, String type, long userId) {
        requireOwnedSession(sessionId, userId);

        List<Message> history = chatMemory.get(sessionId);

        // 不能用 history.isEmpty() 判断"有没有内容"：
        // start() 会把开场提示语（USER 身份的 KICKOFF_CUE）和面试官开场白一起写进记忆，
        // 所以只 start 过、一轮都没答的面试，history 里也躺着 2 条消息。
        // 用 isEmpty() 判断会把它放行，模型只能拿开场白硬编出一份看着完整的复盘
        // —— 实测 0 回答也能给出 54 分、4 个维度，甚至点评从未问过的题。
        // 真正该统计的是"候选人回答了几轮"，所以必须把开场提示语排除掉。
        long answerCount = history == null ? 0L : history.stream()
                .filter(m -> m.getMessageType() == MessageType.USER && !isKickoffCue(m))
                .count();
        if (answerCount == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "这场面试还没有任何回答，无法生成复盘。");
        }

        String transcript = buildTranscript(history);

        // 复盘同样是结构化输出，走统一兜底重试。
        // 注意重试的输入始终是同一份记录 —— 复盘基于已定稿的对话，不会因为重试而改变事实。
        InterviewReport report = StructuredOutputRetry.call(
                "面试复盘",
                () -> reviewClient.prompt()
                        .system(buildReviewPrompt(role, type))
                        .user("以下是这场面试的完整记录，请据此复盘：\n\n" + transcript)
                        .call()
                        .entity(InterviewReport.class),
                InterviewService::isUsable);

        log.info("面试复盘完成：session={} 总分={} 结论={}({})", sessionId,
                report.overallScore(), report.verdict(), report.verdict().label());
        return report;
    }

    /**
     * 复盘结果完整性校验。
     * <p>
     * {@code verdict} 是复盘最核心的产出（这场到底过没过），为空等于没给结论；
     * {@code dimensions} 为空则前端画不出维度条。任一缺失都视为本次结果不可用。
     */
    private static boolean isUsable(InterviewReport r) {
        return r.overallScore() >= 0 && r.overallScore() <= 100
                && r.dimensions() != null && !r.dimensions().isEmpty()
                // verdict 现在是枚举：null 说明模型既没给枚举名、也没给出可识别的中文表述，
                // 属于"这场面试到底过没过都没说"，必须重试，不能当结论落下去。
                && r.verdict() != null
                && StrUtil.isNotBlank(r.summary());
    }

    /**
     * 校验会话归属。
     * <p>
     * sessionId 形如 {@code itv-<userId>-<uuid>}。要求它必须以「当前用户的前缀」开头，
     * 否则就是拿别人的会话 ID 来续面试 / 看复盘 → 403。这是本项目 IDOR 防护
     * 在「无业务表可查归属」场景下的替代做法（面试会话不落 conversation 表）。
     */
    private void requireOwnedSession(String sessionId, long userId) {
        if (StrUtil.isBlank(sessionId) || !sessionId.startsWith(SESSION_PREFIX + userId + "-")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权访问该面试会话。");
        }
    }

    /** 把记忆里的消息拼成「面试官/候选人」对话记录，供复盘使用 */
    private String buildTranscript(List<Message> history) {
        StringBuilder sb = new StringBuilder();
        for (Message m : history) {
            // 开场提示语是 USER 身份发出去的指令，不是候选人的回答，拼记录时跳过
            if (isKickoffCue(m)) {
                continue;
            }
            String text = m.getText() == null ? "" : m.getText();
            String speaker = switch (m.getMessageType()) {
                case USER -> "候选人";
                case ASSISTANT -> "面试官";
                default -> "系统";
            };
            sb.append(speaker).append('：').append(text).append('\n');
        }
        String transcript = sb.toString();
        // 超长面试只保留最近的记录（复盘看近期表现更有价值）
        if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
            transcript = "（记录过长，仅保留最近部分）\n"
                    + transcript.substring(transcript.length() - MAX_TRANSCRIPT_CHARS);
        }
        return transcript;
    }

    /**
     * 这条消息是不是开场的提示语（KICKOFF_CUE）。
     * <p>
     * 它不是候选人的回答 —— 拼复盘记录时要跳过、统计回答轮数时也不能算，
     * 所以判据只留这一处，避免两个地方各写一遍、将来改一处漏一处。
     */
    private static boolean isKickoffCue(Message m) {
        if (m.getMessageType() != MessageType.USER) {
            return false;
        }
        String text = m.getText() == null ? "" : m.getText().trim();
        return KICKOFF_CUE.equals(text);
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /** 面试类型 → 中文说明（非法值一律退回技术面） */
    private String typeDesc(String type) {
        return switch (normalizeType(type)) {
            case "hr" -> "HR 面（重点考察求职动机、稳定性、团队协作、职业规划）";
            case "mixed" -> "综合面（技术与行为面结合，既问技术也问软素质）";
            default -> "技术面（重点考察技术深度、项目细节、底层原理）";
        };
    }

    private String normalizeType(String type) {
        if (type == null) {
            return "tech";
        }
        return switch (type.trim().toLowerCase()) {
            case "hr" -> "hr";
            case "mixed" -> "mixed";
            default -> "tech";
        };
    }

    /**
     * 面试官人设。
     * <p>
     * 这里用 {@code String.replace} 而不是 {@code formatted}——沿用本项目的约定：
     * prompt 里一旦出现字面 {@code %}（比如量化表达示例），{@code formatted} 会把它
     * 当格式占位符解析直接报错。replace 换 token 最稳。
     */
    private String buildInterviewerPrompt(String role, String type) {
        String targetRole = StrUtil.isBlank(role) ? "Java 后端开发" : role.trim();
        return """
                你是一位经验丰富的互联网技术面试官，正在对一位候选人进行一场面试。
                本场面试的类型是：{{TYPE}}
                目标岗位是：{{ROLE}}

                ## 面试规则（必须严格遵守）
                1. 一次只问一个问题，等候选人回答后再决定是追问还是换题。绝不连着抛多个问题。
                2. 像真实面试官一样**追问**：候选人答得含糊，就往下挖一层
                   （「这个方案为什么这么选？」「最大的困难是什么？」「数据量涨十倍会怎样？」）；
                   答到点子上，也可以追一个更深的。考察的是思考过程，不是背答案。
                3. 面试进行中**不要评价、不要解答、不要给建议** —— 那是复盘环节的事。
                   你的职责只是提问和追问。
                4. 问题要贴合目标岗位的真实考察点，由浅入深，一轮比一轮深。
                5. 问满 6 到 8 个问题后，说一句「我的问题问完了」，自然收尾。
                6. 语气专业、简洁，不要过度寒暄，也不要每次都重复「好的，我们继续」。
                """
                .replace("{{TYPE}}", typeDesc(type))
                .replace("{{ROLE}}", targetRole)
                // 面试官也要知道今天几号：否则它可能问出"你今年大几""现在到哪一步了"时
                // 拿训练期的年份做背景，甚至点评候选人经历时用错时间线。统一走 TimeContext。
                + TimeContext.promptSection();
    }

    /** 复盘官人设。同样用 replace 避开 % 坑。 */
    private String buildReviewPrompt(String role, String type) {
        String targetRole = StrUtil.isBlank(role) ? "Java 后端开发" : role.trim();
        return """
                你是一位资深面试官，刚完成一场面试（岗位：{{ROLE}}，类型：{{TYPE}}）。
                现在请基于完整的面试记录，给候选人一份**客观、严格、不留情面**的复盘评估。

                ## 评估维度（固定这 4 项，每项 0~100 分）
                1. 表达与逻辑：回答是否条理清晰、有结构，还是东一句西一句
                2. 技术深度：对技术问题的回答是否准确、深入，还是停留在名词层面
                3. 项目理解：讲项目时是否体现个人贡献、技术选型理由和思考
                4. 临场应变：面对追问、难题时是从容应对还是卡壳

                ## 复盘要求
                - overallScore 是 0~100 的整数，严格打分，大多数候选人的真实水平在 50~75 之间，
                  不要因为"态度好"就给高分
                - verdict 只能取 PASS / PENDING / REJECT 三个**英文枚举名**之一（含义分别是
                  「建议通过」「待定」「不建议通过」），要和分数自洽。
                  直接输出枚举名本身，不要输出中文、不要加引号、不要写成别的近义词。
                - highlights 和 issues 都要**引用面试中的具体问答**，不许泛泛说"技术基础扎实"
                - issues 要说清"这个问题本该答到什么程度"，让候选人知道差距在哪
                - 每条 suggestion 的 fix 要具体可执行（"把 XX 原理补一遍"、"下次讲项目先给结论再展开"）
                - priority 只能取 high / medium / low，按对下一次面试的影响程度定级
                - summary 一句话说清：这场面试能不能过 + 最该补的一个短板
                """
                .replace("{{ROLE}}", targetRole)
                .replace("{{TYPE}}", typeDesc(type))
                // 复盘里会判断"以你现在的进度还能补什么"，同样需要真实日期兜底
                + TimeContext.promptSection();
    }
}
