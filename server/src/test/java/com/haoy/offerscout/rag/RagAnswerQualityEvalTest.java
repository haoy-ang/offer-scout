package com.haoy.offerscout.rag;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.haoy.offerscout.app.JobApp;
import com.haoy.offerscout.common.TextTruncator;
import com.haoy.offerscout.common.TimeContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 *   <li>会往 conversation 表写 {@code eval-judge-*} 开头的会话记录（走的是真实问答链路，不改代码路径）；
 *       同一轮问答还会写进 {@code chat_message} 归档表 —— 这是"评测走真实链路"的必然副作用，
 *       也正是它的价值：如果为了评测绕过业务方法，就测不出归档有没有真的落库。</li>
 *   <li>每次模型调用都套了硬超时（{@link #CALL_TIMEOUT_SECONDS}），超时记失败样本继续跑。
 *       分数里带 {@code -1} 的就是超时/异常，看报告时先看这个数 —— 它高说明上游不稳，不是答得差。</li>
 * </ol>
 */
@Tag("requires-ai")
@SpringBootTest
@ActiveProfiles("local")
// 评测期间关掉「把提示追加到回答末尾」：judge 看到自带免责声明的回答会本能地给高分，
// 那测出来的是"提示写得好不好"，不是"答案有没有变好"。校验逻辑本身照跑（audit() 不看开关）。
@TestPropertySource(properties = "offer-scout.rag.citation-audit-enabled=false")
@Slf4j
class RagAnswerQualityEvalTest {

    /** 默认样本数：够验证通路，又不至于每次跑都心疼额度 */
    private static final int DEFAULT_SAMPLES = 5;

    /**
     * 单次模型调用的硬超时（秒）。
     * <p>
     * <b>为什么评测需要这道闸？</b>因为 DashScope 客户端**没有配读超时** ——
     * 上游一次不返回，调用就会永久挂住。实测过：45 条全量跑到第 6 条时挂死，
     * 33 分钟没有任何进展，`jstack` 打出来 main 线程停在
     * {@code JdkClientHttpRequest.executeInternal → CompletableFuture.get()}（无 timeout 的阻塞等待）。
     * <p>
     * 评测是无人值守跑的（本地也跑，nightly 也跑），绝不能因为一次网络抖动挂死整晚。
     * 所以这里显式加闸：超时就记一条"作答/评分超时"的失败样本，继续跑下一条。
     * <p>
     * ⚠️ {@code cancel(true)} 并不能真的掐断已经阻塞在 socket 读上的线程（JDK HttpClient 的已知限制），
     * 所以线程是 daemon、池会被 {@code shutdownNow} —— 最坏情况是泄漏几个 parked 线程，
     * 但进程能正常退出、报告能正常产出。这是"能跑完"和"挂死"之间可接受的取舍。
     * <p>
     * 这条超时只保护**评测**。生产链路缺读超时是另一个问题（属于限流/熔断那一类），
     * 不该在这里顺手改 —— 改错了会让 Agent 的长任务被误杀。
     */
    private static final int CALL_TIMEOUT_SECONDS = 90;

    @Autowired
    private JobApp jobApp;

    /**
     * 注入的是 {@code @Primary} 的 {@code TokenUsageChatModel} 装饰器 ——
     * 顺带让这次评测的 token 消耗也进 `/ops/usage`，不白烧。
     */
    @Autowired
    private ChatModel chatModel;

    /**
     * 评测必须能拿到**模型当时看到的检索片段**，否则 judge 会把"引用素材"误判成"编造"。
     * <p>
     * 这不是理论担心，是实测踩到的：第一版 judge 只给「问题 + 回答」，
     * 结果凡是回答里出现"某份 JD 要求 4.5–8K"这种**来自知识库素材**的具体细节，
     * judge 都判成"问题里没提供任何上下文，属编造" —— 45 条里 11 条被判编造，
     * 其中大半其实是忠实地转述了检索片段。
     * <p>
     * 用和 {@code JobApp} 完全相同的检索参数（topK=4 + 同一个阈值配置）重现上下文，
     * 是为了让 judge 判断的"依据"和生产里模型看到的"依据"是同一份。
     */
    @Autowired
    private SimpleVectorStore jobVectorStore;

    @Value("${offer-scout.rag.similarity-threshold:0.55}")
    private double similarityThreshold;

    /**
     * 断言校验器。这里直接调 {@code audit()} 而不是 {@code caveatFor()} ——
     * 前者不受开关影响，后者会被上面的 {@code citation-audit-enabled=false} 短路。
     * <p>
     * 这一步是本次评测最想拿到的数据：**checker 在真实答案上到底拦得住多少、误伤多少**。
     * 没有这两个数，就只是在"加了一层看起来能防编造的东西"，不是在解决问题。
     */
    @Autowired
    private CitationAuditService citationAuditService;

    /** judge 的结构化输出 */
    public record JudgeVerdict(int score, boolean grounded, String reason) {
    }

    private record EvalCase(int id, String question, String answer, JudgeVerdict verdict,
                            int blockingCount, String flagged) {
    }

    /**
     * 上一次全量运行留下的「问题 + 回答 + 判分」。
     * <p>
     * <b>为什么要存这个？</b>因为调断言校验的正则时，要反复看"同一批答案在新规则下拦多少、误伤多少"。
     * 每次为此重跑 45 条 = 21 分钟 + 90 次模型调用，而且**样本还不一样**（模型有随机性），
     * 前后两轮的差异分不清是规则改的还是采样抖的。缓存下来之后：
     * {@code -Dreplay=true} 只重算校验、不调模型，几秒钟出结果，且**是同一批答案**——
     * 这才是能用来判断"规则 A 比规则 B 好在哪"的对照。
     */
    private static final Path ANSWER_CACHE = Path.of("target", "eval-reports", "answers-cache.jsonl");

    @Test
    @DisplayName("抽样跑 RAG 问答并让模型按 0-5 打分，输出平均分与低分样本")
    void evaluateAnswerQuality() throws Exception {
        int samples = Integer.getInteger("judge.samples", DEFAULT_SAMPLES);
        List<String> questions = loadPositiveQuestions().stream().limit(samples).toList();
        assertThat(questions).as("评估集里必须有正样本").isNotEmpty();

        boolean replay = Boolean.getBoolean("replay");
        List<EvalCase> cached = replay ? loadAnswerCache() : List.of();
        if (replay && cached.isEmpty()) {
            log.warn("要求回放但 {} 不存在，改为真实调用", ANSWER_CACHE.toAbsolutePath());
        }
        log.info("========== RAG 答案质量评测（{} 条，{}） ==========",
                questions.size(), replay ? "回放缓存" : "真实调用");

        ChatClient judgeClient = ChatClient.builder(chatModel).build();
        List<EvalCase> cases = new ArrayList<>();

        int id = 0;
        for (String question : questions) {
            id++;
            // lambda 只能捕获 effectively final 的变量，循环变量 id 会被改 → 先拷一份
            final int caseId = id;

            String answer;
            JudgeVerdict verdict;
            EvalCase hit = cached.stream().filter(c -> c.id() == caseId).findFirst().orElse(null);

            if (hit != null) {
                answer = hit.answer();
                verdict = hit.verdict();
            } else {
                answer = callWithTimeout("作答#" + caseId,
                        () -> jobApp.doChatWithRag(question, "eval-judge-" + caseId, 0L));
                if (answer == null) {
                    cases.add(new EvalCase(caseId, question, "(超时/异常)",
                            new JudgeVerdict(-1, false, "作答超时或调用异常"), 0, ""));
                    continue;
                }
                if (answer.isBlank()) {
                    cases.add(new EvalCase(caseId, question, "(空回答)",
                            new JudgeVerdict(0, false, "模型未返回内容"), 0, ""));
                    continue;
                }
                verdict = callWithTimeout("评分#" + caseId,
                        () -> judge(judgeClient, question, answer, retrieveContext(question)));
                if (verdict == null) {
                    verdict = new JudgeVerdict(-1, false, "评分超时");
                }
            }

            // 断言校验每次都重算 —— 这正是回放模式的意义：同一批答案，换规则看差异。
            // 两个结果互不影响：一个是确定性规则、一个是模型判断。
            CitationChecker.Result audit = citationAuditService.audit(question, answer);
            String flagged = audit.blocking().stream()
                    .map(CitationChecker.Citation::raw)
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("");
            cases.add(new EvalCase(caseId, question, answer, verdict, audit.blocking().size(), flagged));
        }

        if (!replay) {
            saveAnswerCache(cases);
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

    /** 读上一次的「问题 + 回答 + 判分」，只用于 {@code -Dreplay=true} */
    private List<EvalCase> loadAnswerCache() {
        if (!Files.exists(ANSWER_CACHE)) {
            return List.of();
        }
        List<EvalCase> list = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(ANSWER_CACHE, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                JSONObject o = JSONUtil.parseObj(line);
                list.add(new EvalCase(o.getInt("id"), o.getStr("question"), o.getStr("answer"),
                        new JudgeVerdict(o.getInt("score"), o.getBool("grounded"), o.getStr("reason")),
                        0, ""));
            }
        } catch (Exception e) {
            log.warn("读取答案缓存失败（忽略缓存改走真实调用）：{}", e.getMessage());
            return List.of();
        }
        return list;
    }

    private void saveAnswerCache(List<EvalCase> cases) {
        try {
            Files.createDirectories(ANSWER_CACHE.getParent());
            StringBuilder sb = new StringBuilder();
            for (EvalCase c : cases) {
                sb.append(JSONUtil.createObj()
                        .set("id", c.id())
                        .set("question", c.question())
                        .set("answer", c.answer())
                        .set("score", c.verdict().score())
                        .set("grounded", c.verdict().grounded())
                        .set("reason", c.verdict().reason())
                        .toString()).append('\n');
            }
            Files.writeString(ANSWER_CACHE, sb.toString(), StandardCharsets.UTF_8);
            log.info("答案已缓存到 {}（下次可用 -Dreplay=true 免费重算校验）", ANSWER_CACHE.toAbsolutePath());
        } catch (Exception e) {
            log.warn("写答案缓存失败：{}", e.getMessage());
        }
    }

    /**
     * 重现"模型当时看到的检索片段"。
     * <p>
     * 检索参数必须和 {@code JobApp} 保持一致（topK=4 + 同一个阈值），否则 judge 看到的依据
     * 和模型看到的不是同一份，结论就不可比。
     */    private String retrieveContext(String question) {
        try {
            List<Document> docs = jobVectorStore.similaritySearch(SearchRequest.builder()
                    .query(question)
                    .topK(4)
                    .similarityThreshold(similarityThreshold)
                    .build());
            if (docs == null || docs.isEmpty()) {
                return "(无检索结果)";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < docs.size(); i++) {
                sb.append("【片段").append(i + 1).append("】")
                        .append(TextTruncator.truncateGracefully(docs.get(i).getText(), 800, "…"))
                        .append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "(检索失败：" + e.getMessage() + ")";
        }
    }

    /**
     * 让模型当裁判。刻意把评分维度和"grounded"拆开：
     * 分数看整体质量，grounded 单独看"有没有编"——两者经常不一致，
     * 一个流畅但编造了细节的回答会拿高分却 grounded=false，这才是最危险的一类。
     * <p>
     * <b>必须把检索片段一起给 judge</b>：不给的话，它会用"问题里没提过这些"当判据，
     * 把所有引自素材的具体细节都算成编造 —— 那样测出来的不是"编造率"，是"具体程度"。
     */
    private JudgeVerdict judge(ChatClient judgeClient, String question, String answer, String context) {
        String prompt = """
                你是严格的 RAG 评测员，负责给"求职助手"的回答打分。

                【问题】%s

                【检索到的参考资料】（回答就是基于这些片段生成的，最多 4 段，可能为空）
                %s

                【回答】%s

                评分标准（0-5 整数）：
                5 = 直接回答了问题，内容具体可用，没有编造
                4 = 回答了问题，个别细节空泛但无错误
                3 = 沾边，但泛泛而谈、缺少能直接用的信息
                2 = 大半跑题，只有零星相关内容
                1 = 基本没回答问题
                0 = 编造事实，或与常识/资料矛盾

                grounded 的判据（**按顺序**看，这一步最容易判错）：
                1. 断言能在【参考资料】里找到依据 → **算有依据**，哪怕它很具体（版本号、薪资区间、百分比）。
                   忠实转述素材**不算编造**。上一版评测漏了这条，把大量"引用"误判成"编造"。
                2. 参考资料里没有，但在 Java 后端通用技术常识里成立 → 算有依据。
                3. 两处都没有，且像是"为了显得具体而生成的" → grounded=false。
                4. 【参考资料】为空时：回答若给出任何具体版本号 / 配置项 / 量化数据 → 一律 grounded=false。

                只输出 JSON，不要解释：
                {"score": 3, "grounded": true, "reason": "一句话说明扣分点"}
                """.formatted(question, context, answer)
                // judge 也必须知道今天几号。实测漏了这一条会稳定误判：
                // 回答里写"2026 年主流实践"（今天确实是 2026-10），judge 却按自己的训练期认为"现在不是 2026 年"，
                // 于是把一句正确的话判成"时间断言虚构"。作答侧早就接了 TimeContext，判分侧漏了 —— 判据要一致。
                + TimeContext.promptSection();

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

    /**
     * 带硬超时地跑一次模型调用（超时返回 null，由调用方记成失败样本）。
     * <p>
     * 每次调用单独起一个 daemon 线程、用完 {@code shutdownNow}：
     * 目的是"跑不完就别跑了"，不是线程复用 —— 评测一晚上才跑一次，没必要为省一个线程引入池管理的复杂度。
     */
    private <T> T callWithTimeout(String what, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "eval-" + what);
            t.setDaemon(true);
            return t;
        });
        try {
            Future<T> future = pool.submit(task);
            try {
                return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("{} 超过 {}s 未返回，记为失败样本并继续（上游无读超时，见 CALL_TIMEOUT_SECONDS 注释）",
                        what, CALL_TIMEOUT_SECONDS);
                return null;
            } catch (ExecutionException e) {
                log.warn("{} 调用异常：{}", what, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                return null;
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private String buildReport(List<EvalCase> cases) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n### RAG 答案质量评测（LLM-as-judge，样本 ").append(cases.size()).append(" 条）\n\n");
        sb.append("| # | 得分 | grounded | 断言拦截 | 扣分点 |\n|---|---|---|---|---|\n");
        for (EvalCase c : cases) {
            sb.append(String.format("| %d | %d | %s | %s | %s |\n",
                    c.id(), c.verdict().score(),
                    c.verdict().grounded() ? "是" : "**否**",
                    c.blockingCount() == 0 ? "-" : "**" + c.blockingCount() + "**：" + c.flagged(),
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
        sb.append("> ✅ **grounded 已改为「上下文感知」**：judge 现在能看到检索片段，"
                + "「引自素材」不再被算成编造 —— 上一版没有片段，判出来的其实是「具体程度」而不是「编造率」。\n");
        sb.append("> ⚠️ judge 与作答同模型，分数偏宽松；**只看同一项目内部的相对变化，不要跨项目比较绝对值**。\n");

        appendCitationSection(sb, cases);
        return sb.toString();
    }

    /**
     * 断言校验器的效果面。
     * <p>
     * 这里只能算**代理指标**，说清楚免得自欺：
     * <ul>
     *   <li><b>拦截率</b> = judge 判 grounded=false 的样本里，被 checker 标出具体值的比例。
     *       分母里混着"拒绝作答"这类没有数值可拦的样本（judge 也判它 grounded=false），
     *       所以这个数是<b>下限</b>，真实召回更高。</li>
     *   <li><b>误伤面</b> = judge 判 grounded=true 的样本里被标出的比例。
     *       但 grounded=true 不等于"每个数字都有出处" —— 有些正确回答确实会给出资料外的真实常识
     *       （比如举例说明"P99 该怎么测"）。所以这个数是<b>上限</b>，真实误报更低。</li>
     * </ul>
     * 两者都朝保守方向偏，正好可以用来判断"这一层能不能默认开"。
     */
    private void appendCitationSection(StringBuilder sb, List<EvalCase> cases) {
        List<EvalCase> judged = cases.stream().filter(c -> c.verdict().score() >= 0).toList();
        if (judged.isEmpty()) {
            return;
        }
        List<EvalCase> bad = judged.stream().filter(c -> !c.verdict().grounded()).toList();
        List<EvalCase> good = judged.stream().filter(c -> c.verdict().grounded()).toList();
        long badCaught = bad.stream().filter(c -> c.blockingCount() > 0).count();
        long goodFlagged = good.stream().filter(c -> c.blockingCount() > 0).count();

        sb.append("\n#### 断言校验（CitationChecker）效果面\n\n");
        sb.append("| 指标 | 值 | 说明 |\n|---|---|---|\n");
        sb.append(String.format("| 拦截率（代理，下限） | %d / %d | judge 判「不 grounded」且被标出具体值 |\n",
                badCaught, bad.size()));
        sb.append(String.format("| 误伤面（代理，上限） | %d / %d | judge 判「grounded」却仍被标出 |\n",
                goodFlagged, good.size()));
        sb.append(String.format("| 零拦截样本 | %d / %d | 没有可拦的具体数值，或全部有出处 |\n",
                judged.stream().filter(c -> c.blockingCount() == 0).count(), judged.size()));

        if (goodFlagged > 0) {
            sb.append("\n> 「grounded 但被标出」的样本需要人眼复核是真误报、还是 judge 判松了：\n");
            good.stream().filter(c -> c.blockingCount() > 0).forEach(c ->
                    sb.append("> - #").append(c.id()).append("：").append(c.flagged()).append('\n'));
        }
        sb.append("\n> ⚠️ 两个都是代理指标：拦截率是<b>下限</b>（分母含无值可拦的拒答样本），"
                + "误伤面是<b>上限</b>（grounded=true 不等于每个数字都有出处）。真实值要人眼抽查。\n");
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
