package com.haoy.offerscout.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 大模型调用的 token / 耗时记账。
 * <p>
 * <b>为什么需要它？</b>这个项目每个功能都是按量计费的大模型调用，但之前
 * <b>没有任何地方记录过花了多少 token</b> —— 意味着：不知道一天烧多少钱、
 * 不知道哪个功能最贵、没法做预算、也没法和"用户量"对上账。
 * 这在调 LLM 的系统里是比"答得准不准"更基础的一件事：<b>没有计量就没有成本控制</b>。
 * <p>
 * <b>统计口径分三层：</b>
 * <ul>
 *   <li>全局：总调用次数 / 总 token / 总耗时 —— 回答"我一共花了多少"；</li>
 *   <li>按场景：问答 / 简历诊断 / 岗位匹配 / 面试 / Agent —— 回答"哪个功能最贵"，这才是能指导优化的维度；</li>
 *   <li>按模型：换模型前后可以直接对比。</li>
 * </ul>
 * <b>为什么用内存聚合而不是落库？</b>个人项目先解决"看得见"，写库要建表 + 处理并发写 +
 * 加清理策略，投入产出不划算。代价必须说清楚：<b>进程重启计数清零，多实例各算一份</b>。
 * 要做长期成本分析，得换成"每次调用落一行 + 按天聚合"，那是另一件事。
 * <p>
 * 计数用 {@link AtomicLong} 而不是 {@code synchronized}：写多读少、只做累加，
 * 无锁 CAS 足够且不会成为热点。精确到每一次调用不是目标，<b>趋势和量级</b>才是。
 */
@Component
@Slf4j
public class TokenUsageRecorder {

    /** 日志前缀。想统计成本就 `grep '\[token\]' app.log`，比翻散落各处的日志靠谱 */
    public static final String LOG_PREFIX = "[token]";

    /** 只保留最近 N 条明细，防止长时间运行把内存吃满 */
    private static final int RECENT_LIMIT = 100;

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong totalPromptTokens = new AtomicLong();
    private final AtomicLong totalCompletionTokens = new AtomicLong();
    private final AtomicLong totalCostMillis = new AtomicLong();
    /** usage 缺失的调用数 —— 这个数字应该尽量接近 0，涨起来说明统计不可信了 */
    private final AtomicLong usageMissingCalls = new AtomicLong();

    private final Map<String, Agg> byScene = new ConcurrentHashMap<>();
    private final Map<String, Agg> byModel = new ConcurrentHashMap<>();
    private final Deque<CallRecord> recent = new ConcurrentLinkedDeque<>();

    /**
     * 记一次调用。
     *
     * @param scene            场景名（见 {@link TokenUsageScene}）；拿不到时记为 unknown
     * @param model            模型名，可为 null
     * @param promptTokens     输入 token；usage 缺失时传 0
     * @param completionTokens 输出 token；usage 缺失时传 0
     * @param costMillis       端到端耗时（毫秒）
     * @param streamed         是否流式调用
     * @param usagePresent     供应商有没有把 usage 返回给我们
     */
    public void record(String scene, String model, int promptTokens, int completionTokens,
                       long costMillis, boolean streamed, boolean usagePresent) {
        String safeScene = (scene == null || scene.isBlank()) ? "unknown" : scene;
        String safeModel = (model == null || model.isBlank()) ? "unknown" : model;

        totalCalls.incrementAndGet();
        totalPromptTokens.addAndGet(promptTokens);
        totalCompletionTokens.addAndGet(completionTokens);
        totalCostMillis.addAndGet(costMillis);
        if (!usagePresent) {
            usageMissingCalls.incrementAndGet();
        }

        byScene.computeIfAbsent(safeScene, k -> new Agg()).add(promptTokens, completionTokens, costMillis, usagePresent);
        byModel.computeIfAbsent(safeModel, k -> new Agg()).add(promptTokens, completionTokens, costMillis, usagePresent);

        addRecent(new CallRecord(Instant.now(), safeScene, safeModel, promptTokens, completionTokens,
                costMillis, streamed, usagePresent));

        // 一行一条，字段固定顺序，方便 awk / grep 直接切
        log.info("{} scene={} model={} in={} out={} cost={}ms stream={} usage={}",
                LOG_PREFIX, safeScene, safeModel, promptTokens, completionTokens,
                costMillis, streamed, usagePresent ? "ok" : "missing");
    }

    private void addRecent(CallRecord record) {
        recent.addLast(record);
        // 并发下可能略超上限几十条，无所谓；这里要的是"有界"，不是"精确 100"
        while (recent.size() > RECENT_LIMIT) {
            recent.pollFirst();
        }
    }

    /** 给运维接口用的快照。字段名直白优先，别为了好看做嵌套花活 */
    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("totalCalls", totalCalls.get());
        root.put("promptTokens", totalPromptTokens.get());
        root.put("completionTokens", totalCompletionTokens.get());
        root.put("totalTokens", totalPromptTokens.get() + totalCompletionTokens.get());
        root.put("avgCostMillis", totalCalls.get() == 0 ? 0
                : Math.round((double) totalCostMillis.get() / totalCalls.get()));
        root.put("usageMissingCalls", usageMissingCalls.get());
        root.put("byScene", toPlainMap(byScene));
        root.put("byModel", toPlainMap(byModel));
        root.put("recent", recentSnapshot());
        return root;
    }

    private Map<String, Object> toPlainMap(Map<String, Agg> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Agg> e) -> e.getValue().completionTokens.get())
                        .reversed())
                .forEach(e -> result.put(e.getKey(), e.getValue().snapshot()));
        return result;
    }

    private List<CallRecord> recentSnapshot() {
        List<CallRecord> list = new ArrayList<>(recent);
        // 新的在前
        java.util.Collections.reverse(list);
        return list.subList(0, Math.min(20, list.size()));
    }

    /** 单条调用明细 */
    public record CallRecord(Instant at, String scene, String model, int promptTokens, int completionTokens,
                             long costMillis, boolean streamed, boolean usagePresent) {
    }

    /** 按维度聚合的累加器 */
    private static final class Agg {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();
        private final AtomicLong costMillis = new AtomicLong();
        private final AtomicLong usageMissing = new AtomicLong();

        void add(int prompt, int completion, long cost, boolean usagePresent) {
            calls.incrementAndGet();
            promptTokens.addAndGet(prompt);
            completionTokens.addAndGet(completion);
            costMillis.addAndGet(cost);
            if (!usagePresent) {
                usageMissing.incrementAndGet();
            }
        }

        Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", calls.get());
            m.put("promptTokens", promptTokens.get());
            m.put("completionTokens", completionTokens.get());
            m.put("totalTokens", promptTokens.get() + completionTokens.get());
            m.put("avgCostMillis", calls.get() == 0 ? 0 : Math.round((double) costMillis.get() / calls.get()));
            m.put("usageMissingCalls", usageMissing.get());
            return m;
        }
    }
}
