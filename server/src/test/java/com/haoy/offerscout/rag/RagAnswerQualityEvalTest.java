package com.haoy.offerscout.rag;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.haoy.offerscout.app.JobApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 答案质量评测（LLM-as-judge）。
 * <p>
 * 跑法：
 * <pre>
 * ./mvnw test -Dtest=RagAnswerQualityEvalTest                    # 默认只跑 5 条
 * ./mvnw test -Dtest=RagAnswerQualityEvalTest -Djudge.samples=45 # 全量（45 条，约 1 元以内）
 * </pre>
 * <p>
 * <b>和 {@link RagEvaluationRunnerTest} 的分工</b>：那个测「检索层」—— 该召回的片段召回了没有、
 * 阈值定多少；这个测「生成层」—— 拿到片段之后答得对不对。**关键词命中率高 ≠ 答得好**，
 * 模型完全可能把召回到的正确资料说错，只有 judge 才能发现。
 * <p>
 * <b>为什么默认只跑 5 条</b>：每条要 2 次模型调用（作答 + 评分），45 条就是 90 次，
 * 按量计费的项目不该让"跑一次评估"变成负担。5 条够验证通路与 prompt 是否可用，
 * 需要统计意义时显式传 {@code -Djudge.samples=45}。
 * <p>
 * <b>已知局限（说清楚比藏着好）</b>：
 * <ol>
 *   <li><b>自己评自己</b>：judge 和作答用的是同一个 DashScope 模型，会系统性偏宽松。
 *       要更客观得换一家模型当裁判（本项目只有一家 Key）。</li>
 *   <li>分数绝对值不可跨项目比较，只看<b>同一项目内不同版本之间的相对变化</b>。</li>
 *   <li>会往 conversation 表写 {@code eval-judge-*} 开头的会话记录（走的是真实问答链路，不改代码路径）。</li>
 * </ol>
 */
@Tag("requires-ai")
@SpringBootTest
@ActiveProfiles("local")
@Slf4j
class RagAnswerQualityEvalTest {

    /** 默认样本数：够验证通路，又不至于每次跑都心疼额度 */
    private static final int DEFAULT_SAMPLES = 5;

    @Autowired
    private JobApp jobApp;

    /**
     * 注入的是 {@code @Primary} 的 {@code TokenUsageChatModel} 装饰器 ——
     * 顺带让这次评测的 token 消耗也进 `/ops/usage`，不白烧。
     */
    @Autowired
    private ChatModel chatModel;

    /** judge 的结构化输出 */
    public record JudgeVerdict(int score, boolean grounded, String reason) {
    }

    private record EvalCase(int id, String question, String answer, JudgeVerdict verdict) {
    }

    @Test
    @DisplayName("抽样跑 RAG 问答并让模型按 0-5 打分，输出平均分与低分样本")
    void evaluateAnswerQuality() throws Exception {
        int samples = Integer.getInteger("judge.samples", DEFAULT_SAMPLES);
        List<String> questions = loadPositiveQuestions().stream().limit(samples).toList();
        assertThat(questions).as("评估集里必须有正样本").isNotEmpty();

        ChatClient judgeClient = ChatClient.builder(chatModel).build();
        List<EvalCase> cases = new ArrayList<>();

        log.info("========== RAG 答案质量评测（{} 条） ==========", questions.size());
        int id = 0;
        for (String question : questions) {
            id++;
            String answer = jobApp.doChatWithRag(question, "eval-judge-" + id, 0L);
            if (answer == null || answer.isBlank()) {
                cases.add(new EvalCase(id, question, "(空回答)", new JudgeVerdict(0, false, "模型未返回内容")));
                continue;
            }
            JudgeVerdict verdict = judge(judgeClient, question, answer);
            cases.add(new EvalCase(id, question, answer, verdict));
        }

        String report = buildReport(cases);
        writeReport("answer-quality", report);
        log.info(report);
        System.out.println(report);
    }

    /**
     * 报告落盘到 {@code target/eval-reports/}。
     * <p>
     * 不能只靠 {@code System.out}：Windows 上 mvn 的 stdout 经过 cmd.exe 中转，
     * PowerShell 再按 GBK 解码，中文会被"烙"成乱码且不可逆。直接写 UTF-8 文件最省事。
     */
    private void writeReport(String name, String content) {
        try {
            Path dir = Path.of("target", "eval-reports");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".md"), content, StandardCharsets.UTF_8);
            log.info("评估报告已写入 {}", dir.resolve(name + ".md").toAbsolutePath());
        } catch (Exception e) {
            log.warn("写评估报告失败：{}", e.getMessage());
        }
    }

    /**
     * 让模型当裁判。刻意把评分维度和"grounded"拆开：
     * 分数看整体质量，grounded 单独看"有没有编"——两者经常不一致，
     * 一个流畅但编造了细节的回答会拿高分却 grounded=false，这才是最危险的一类。
     */
    private JudgeVerdict judge(ChatClient judgeClient, String question, String answer) {
        String prompt = """
                你是严格的 RAG 评测员，负责给"求职助手"的回答打分。

                【问题】%s

                【回答】%s

                评分标准（0-5 整数）：
                5 = 直接回答了问题，内容具体可用，没有编造
                4 = 回答了问题，个别细节空泛但无错误
                3 = 沾边，但泛泛而谈、缺少能直接用的信息
                2 = 大半跑题，只有零星相关内容
                1 = 基本没回答问题
                0 = 编造事实，或与常识/资料矛盾

                另外判断 grounded：回答里的事实性断言（版本号、参数名、结论）是否都能在
                Java 后端常见面经知识中找到依据。找不到依据的具体断言算作编造，grounded=false。

                只输出 JSON，不要解释：
                {"score": 3, "grounded": true, "reason": "一句话说明扣分点"}
                """.formatted(question, answer);

        try {
            JudgeVerdict verdict = judgeClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(JudgeVerdict.class);
            return verdict == null ? new JudgeVerdict(-1, false, "评分解析失败") : verdict;
        } catch (Exception e) {
            log.warn("judge 调用失败：{}", e.getMessage());
            return new JudgeVerdict(-1, false, "评分调用异常：" + e.getMessage());
        }
    }

    private String buildReport(List<EvalCase> cases) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n### RAG 答案质量评测（LLM-as-judge，样本 ").append(cases.size()).append(" 条）\n\n");
        sb.append("| # | 得分 | grounded | 扣分点 |\n|---|---|---|---|\n");
        for (EvalCase c : cases) {
            sb.append(String.format("| %d | %d | %s | %s |\n",
                    c.id(), c.verdict().score(),
                    c.verdict().grounded() ? "是" : "**否**",
                    c.verdict().reason() == null ? "" : c.verdict().reason().replace("|", "/")));
        }

        double avg = cases.stream().filter(c -> c.verdict().score() >= 0)
                .mapToInt(c -> c.verdict().score()).average().orElse(-1);
        long ungrounded = cases.stream().filter(c -> !c.verdict().grounded()).count();
        long failed = cases.stream().filter(c -> c.verdict().score() < 0).count();

        sb.append("\n> **汇总**：平均分 ");
        sb.append(avg < 0 ? "N/A" : String.format("%.2f", avg));
        sb.append(" / 5，**未通过 grounded 校验 ").append(ungrounded).append(" 条**");
        sb.append("（其中最危险的是「高分但不 grounded」：读起来很顺、细节是编的）；");
        sb.append("评分失败 ").append(failed).append(" 条。\n");
        sb.append("> ⚠️ judge 与作答同模型，分数偏宽松；**只看同一项目内部的相对变化，不要跨项目比较绝对值**。\n");
        return sb.toString();
    }

    private List<String> loadPositiveQuestions() throws Exception {
        ClassPathResource resource = new ClassPathResource("eval/rag-eval-set.jsonl");
        List<String> questions = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                JSONObject json = JSONUtil.parseObj(line);
                if (!json.getBool("expectNoHit", false)) {
                    questions.add(json.getStr("question"));
                }
            }
        }
        return questions;
    }
}
