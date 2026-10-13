package com.haoy.offerscout.resume;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 简历诊断业务逻辑。
 * <p>
 * 流程分三步：
 * <ol>
 *   <li><b>拿到简历文本</b>：优先解析上传的 PDF，没有就取用户直接粘贴的文本（兜底）。</li>
 *   <li><b>检索简历写法参考</b>：从知识库召回「简历怎么写」的资料，让诊断有据可依，
 *       而不是模型凭训练记忆空谈。</li>
 *   <li><b>结构化诊断</b>：把简历 + 目标岗位 + 参考资料一起交给大模型，
 *       用 {@code .entity(ResumeDiagnosis.class)} 强制输出成固定结构。</li>
 * </ol>
 * <p>
 * <b>为什么这里要新起一个 ChatClient，而不是复用 JobApp 里的？</b>
 * JobApp 的 ChatClient 挂了「对话记忆 Advisor」—— 每次请求都会读写历史消息。
 * 简历诊断是**无状态的一次性调用**，不该沾染任何会话记忆（否则上一份简历的内容
 * 会泄漏进下一份的诊断里）。所以这里用不带 Advisor 的裸 Client。
 */
@Service
@Slf4j
public class ResumeService {

    /** 粘贴文本同样要截断，和 PDF 解析的截断口径保持一致 */
    private static final int MAX_RESUME_CHARS = 15000;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ResumeParser resumeParser;

    public ResumeService(ChatModel chatModel, VectorStore jobVectorStore, ResumeParser resumeParser) {
        // 裸 Client：没有 system 默认值、没有记忆 Advisor，一切都在 analyze() 里现拼
        this.chatClient = ChatClient.builder(chatModel).build();
        this.vectorStore = jobVectorStore;
        this.resumeParser = resumeParser;
    }

    /**
     * 诊断一份简历。
     *
     * @param file      上传的 PDF（可为空）
     * @param pastedText 直接粘贴的文本（可为空，作为 PDF 解析失败的兜底）
     * @param targetJob 目标岗位描述（可为空，填了会影响诊断口径）
     */
    public ResumeDiagnosis analyze(MultipartFile file, String pastedText, String targetJob) {
        String resumeText = resolveResumeText(file, pastedText);
        String reference = searchResumeTips(targetJob);
        return diagnose(resumeText, targetJob, reference);
    }

    /** 简历文本来源：PDF 优先，粘贴文本兜底，都没有就报错 */
    private String resolveResumeText(MultipartFile file, String pastedText) {
        boolean hasFile = file != null && !file.isEmpty();
        if (hasFile) {
            return resumeParser.extractText(file);
        }
        if (StrUtil.isNotBlank(pastedText)) {
            String text = pastedText.trim();
            return text.length() > MAX_RESUME_CHARS ? text.substring(0, MAX_RESUME_CHARS) : text;
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "请上传简历文件，或直接粘贴简历文本。");
    }

    /** 从知识库召回简历写法资料，拼成给模型的参考文本 */
    private String searchResumeTips(String targetJob) {
        String query = StrUtil.isNotBlank(targetJob)
                ? targetJob + " 简历要求 岗位匹配 项目亮点"
                : "技术简历写法 量化表达 项目亮点 STAR";
        try {
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(4)
                    .similarityThreshold(0.0)
                    .build());
            if (docs == null || docs.isEmpty()) {
                return "（知识库暂未检索到简历写法资料，请基于通用简历标准诊断）";
            }
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (Document doc : docs) {
                String text = doc.getText() == null ? "" : doc.getText().replaceAll("\\s+", " ").trim();
                if (text.length() > 800) {
                    text = text.substring(0, 800) + "…";
                }
                sb.append("【参考 ").append(i++).append("｜")
                        .append(doc.getMetadata().getOrDefault("filename", "未知"))
                        .append("】\n").append(text).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            // 检索失败不阻断诊断：降级为"没有参考"，让模型用通用标准
            log.warn("简历写法检索失败，降级为无参考诊断", e);
            return "（知识库检索失败，请基于通用简历标准诊断）";
        }
    }

    private ResumeDiagnosis diagnose(String resumeText, String targetJob, String reference) {
        String systemPrompt = buildSystemPrompt(reference);
        String userPrompt = buildUserPrompt(resumeText, targetJob);

        try {
            ResumeDiagnosis diagnosis = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(ResumeDiagnosis.class);
            log.info("简历诊断完成：总分 {}，建议 {} 条", diagnosis.overallScore(),
                    diagnosis.suggestions() == null ? 0 : diagnosis.suggestions().size());
            return diagnosis;
        } catch (Exception e) {
            // 结构化输出失败最常见的原因是模型没按 schema 吐 JSON。
            // 这里不尝试裸奔返回 —— 把错误抛给全局异常处理，前端能看到明确失败而不是一张空报告。
            log.error("简历诊断结构化输出失败（模型可能未按 JSON 格式返回）", e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "诊断服务暂时不可用，请稍后重试。");
        }
    }

    private String buildSystemPrompt(String reference) {
        return """
                你是一名资深的互联网技术岗简历诊断专家，阅简历无数，也做过多年面试官。
                你的任务是：拿到一份候选人的简历，给出**具体、可执行、不留情面**的诊断。

                ## 评分维度（固定这 4 项，每项 0~100 分）
                1. 技术栈清晰度：技能是否写清、是否有层次，而不是罗列一堆名词
                2. 项目经历深度：项目描述是否体现了难度、个人贡献和思考，而不是流水账
                3. 量化表达：是否有数据、有对比、有结果（性能提升 X%、处理量 Y、排名 Z 等）
                4. 表达与结构：排版是否易扫读、语言是否精炼、有没有废话和错别字

                ## 诊断要求
                - 总分 overallScore 是 0~100 的整数，不要给 90 分以上的"人情分"，大多数简历的真实水平在 50~75 之间
                - 每条 suggestion 的 problem 要**尽量引用简历里的原文或描述具体现象**，不许泛泛说"项目描述太弱"
                - 每条 suggestion 的 fix 要给出**具体改法**（"把 XX 改成 XX"），不许说"多练、多写、多积累"
                - priority 只能取 high / medium / low 三个值，按对拿到面试的影响程度定级
                - 如果用户给了目标岗位，所有诊断都要围绕"匹配这个岗位"展开，明确指出简历里
                  和该岗位不匹配或缺失的部分；没给岗位就按"后端方向"的通用标准来
                - summary 一句话说清：这份简历最大的优势 + 最该马上改的一个点

                ## 简历写法参考（来自知识库，优先采用其中的标准）
                {{REFERENCE}}
                """
                // 注意：这里刻意不用 String.formatted()。提示词里出现了字面 "%"（"性能提升 X%"），
                // formatted() 会把所有 "%" 当成格式占位符解析，撞上 "%、" 这种非法转换直接抛
                // UnknownFormatConversionException。用 replace 换一个不会出现在正文里的 token 最稳。
                .replace("{{REFERENCE}}", reference);
    }

    private String buildUserPrompt(String resumeText, String targetJob) {
        String jobPart = StrUtil.isNotBlank(targetJob)
                ? "目标岗位：\n" + targetJob.trim()
                : "目标岗位：未提供，按后端开发方向通用标准诊断";
        return "以下是候选人简历全文，请诊断：\n\n" + jobPart
                + "\n\n===== 简历正文开始 =====\n" + resumeText + "\n===== 简历正文结束 =====";
    }
}
