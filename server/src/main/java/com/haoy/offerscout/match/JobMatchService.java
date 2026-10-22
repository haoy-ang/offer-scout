package com.haoy.offerscout.match;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.common.BusinessException;
import com.haoy.offerscout.common.StructuredOutputRetry;
import com.haoy.offerscout.common.TextTruncator;
import com.haoy.offerscout.common.TimeContext;
import com.haoy.offerscout.resume.ResumeParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 岗位匹配业务逻辑：拿一份简历对一份 JD，算出「离这个岗位差多远」。
 * <p>
 * 流程比简历诊断更短，只有两步：
 * <ol>
 *   <li><b>拿到简历文本</b>：复用 {@link ResumeParser}（PDF 优先、粘贴文本兜底）。</li>
 *   <li><b>结构化匹配</b>：把简历 + JD 一起交给大模型，用 {@code .entity(JobMatch.class)} 输出匹配报告。</li>
 * </ol>
 * <p>
 * <b>为什么这里不检索知识库，而简历诊断要？</b>
 * 诊断需要「一份好简历长什么样」这个外部标准，所以要去知识库召回写法资料；
 * 匹配的标准就是 **JD 本身**（它要什么技能、什么经验，白纸黑字写着），
 * 直接拿简历和 JD 逐条对照即可，多一次知识库检索既慢又费一次 embedding 的钱。
 * <p>
 * <b>同样用裸 ChatClient</b>：匹配是无状态的一次性调用，不能沾染 {@code JobApp} 的会话记忆。
 */
@Service
@Slf4j
public class JobMatchService {

    /** 简历文本截断口径，和 ResumeParser 保持一致 */
    private static final int MAX_RESUME_CHARS = 15000;

    /** JD 通常不会太长，但也要防住有人粘一篇公众号长文进来 */
    private static final int MAX_JD_CHARS = 8000;

    private final ChatClient chatClient;
    private final ResumeParser resumeParser;

    public JobMatchService(ChatModel chatModel, ResumeParser resumeParser) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.resumeParser = resumeParser;
    }

    /**
     * 匹配一份简历和一个岗位。
     *
     * @param file       简历 PDF（可为空）
     * @param pastedText 简历文本（可为空，作为 PDF 的兜底）
     * @param jd         岗位 JD 文本（必填 —— 没有 JD 就无从匹配）
     */
    public JobMatch analyze(MultipartFile file, String pastedText, String jd) {
        String resumeText = resolveResumeText(file, pastedText);
        String jdText = resolveJd(jd);
        return match(resumeText, jdText);
    }

    private String resolveResumeText(MultipartFile file, String pastedText) {
        if (file != null && !file.isEmpty()) {
            return resumeParser.extractText(file);
        }
        if (StrUtil.isNotBlank(pastedText)) {
            return TextTruncator.truncateGracefully(pastedText.trim(), MAX_RESUME_CHARS, "…（已截断）");
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "请上传简历文件，或直接粘贴简历文本。");
    }

    private String resolveJd(String jd) {
        if (StrUtil.isBlank(jd)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请粘贴目标岗位的 JD（职位描述）。");
        }
        return TextTruncator.truncateGracefully(jd.trim(), MAX_JD_CHARS, "…（已截断）");
    }

    private JobMatch match(String resumeText, String jdText) {
        JobMatch result = StructuredOutputRetry.call(
                "岗位匹配",
                () -> chatClient.prompt()
                        .system(buildSystemPrompt())
                        .user(buildUserPrompt(resumeText, jdText))
                        .call()
                        .entity(JobMatch.class),
                JobMatchService::isUsable);

        log.info("岗位匹配完成：匹配度 {}，命中 {} 条，差距 {} 条",
                result.overallScore(),
                result.strengths() == null ? 0 : result.strengths().size(),
                result.gaps() == null ? 0 : result.gaps().size());
        return result;
    }

    /**
     * 匹配结果完整性校验。
     * <p>
     * 这里额外要求 {@code strengths} / {@code gaps} 至少有一个非空：一份"没有命中点也没有差距点"
     * 的匹配报告没有任何信息量，属于模型没真正干活，应该重试而不是原样返回给用户。
     */
    private static boolean isUsable(JobMatch m) {
        return m.overallScore() >= 0 && m.overallScore() <= 100
                && m.dimensions() != null && !m.dimensions().isEmpty()
                && StrUtil.isNotBlank(m.summary())
                && ((m.strengths() != null && !m.strengths().isEmpty())
                    || (m.gaps() != null && !m.gaps().isEmpty()));
    }

    private String buildSystemPrompt() {
        return """
                你是一名资深的技术岗招聘专家，既看过海量简历，也写过大量 JD、做过多年面试官。
                你的任务是：拿一份候选人简历，对照一个岗位 JD，算出「这份简历离这个岗位差多远」。

                ## 评分维度（固定这 4 项，每项 0~100 分）
                1. 技术栈匹配：简历技能是否覆盖 JD 要求的核心技术栈
                2. 项目经历匹配：项目方向、难度、深度是否对得上岗位要求
                3. 学历与背景：学历层次、专业、工作经验年限等硬性条件是否达标
                4. 综合素质：学习能力、沟通协作、主动性等软性要求

                ## 匹配要求
                - 总分 overallScore 是 0~100 的整数。判据要严格：JD 的核心要求没覆盖，分数就该低，
                  不要因为"这个人看着还不错"就给高分。大多数投递的真实匹配度在 40~75 之间
                - strengths（命中点）和 gaps（差距点）是核心：每条都要**具体到 JD 里的一句话要求**，
                  requirement 尽量原样引用 JD 原文，detail 写简历里对应的证据或现状
                - 差距点尤其要说透：不只是"缺这个技能"，还要说清"缺了它对这个岗位意味着什么"
                - 每条 suggestion 的 fix 要给**具体可执行动作**（"补一个带 XX 的项目"、"把 XX 经历改成
                  突出 XX"），不许说"多学、多练"
                - priority 只能取 high / medium / low，按「补上它能不能直接提高面试通过率」定级
                - summary 一句话说清：整体值不值得投 + 最致命的一个差距

                ## 输出约束
                - 只输出符合 JSON Schema 的结构化结果，不要输出任何额外的解释文字
                """
                // 补「今天几号」：判断"应届/几届/现在的时间点"必须基于真实日期，
                // 否则模型会拿训练数据里的年份当"现在"。口径统一走 TimeContext。
                + TimeContext.promptSection();
    }

    private String buildUserPrompt(String resumeText, String jdText) {
        return "请对照下面的岗位 JD，评估候选人简历的匹配度。\n\n"
                + "===== 岗位 JD 开始 =====\n" + jdText + "\n===== 岗位 JD 结束 =====\n\n"
                + "===== 候选人简历开始 =====\n" + resumeText + "\n===== 候选人简历结束 =====";
    }
}
