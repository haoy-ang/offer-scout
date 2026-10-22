package com.haoy.offerscout.controller;

import com.haoy.offerscout.rag.JobVectorStoreStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口。分成两个语义，别混用：
 * <ul>
 *   <li><b>{@code /health/check} —— 存活探针（liveness）</b>：进程活着、能响应 HTTP 就返回 ok。
 *       它<b>不</b>检查任何依赖，因为"MySQL 挂了"不该触发重启进程。</li>
 *   <li><b>{@code /health/ready} —— 就绪探针（readiness）</b>：把依赖的真实状态摊开给人和监控看，
 *       目前重点是知识库（RAG）。</li>
 * </ul>
 * <b>为什么单独加 /ready 而不是改 /check 的返回值？</b>{@code /check} 返回的是纯文本 {@code "ok"}，
 * 已经被部署脚本和本地验证脚本当作字符串判断 —— 改成 JSON 会静默打断这些调用方。
 * 新语义开新端点，是兼容性成本最低的做法。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private final JobVectorStoreStatus vectorStoreStatus;

    public HealthController(JobVectorStoreStatus vectorStoreStatus) {
        this.vectorStoreStatus = vectorStoreStatus;
    }

    /** 存活探针：只要进程在就是 ok */
    @GetMapping("/check")
    public String healthCheck() {
        return "ok";
    }

    /**
     * 就绪探针：知识库能不能真的检索出东西。
     * <p>
     * <b>注意这里刻意不返回 503。</b>知识库挂掉时应用仍能提供大部分功能（只是回答质量下降、
     * 更容易瞎编），把实例从负载均衡里摘掉反而会让服务彻底不可用 —— 这是"降级"不是"不可用"。
     * 所以用 200 + {@code status=degraded} 表达，让监控按字段告警，而不是靠状态码。
     */
    @GetMapping("/ready")
    public ResponseEntity<Readiness> readiness() {
        boolean searchable = vectorStoreStatus.isSearchable();
        KnowledgeBase kb = new KnowledgeBase(
                vectorStoreStatus.getState().name(),
                searchable,
                vectorStoreStatus.getChunks(),
                // 毫秒转秒，保留一位小数：日志里看 190.3 比 190300 直观
                Math.round(vectorStoreStatus.getBuildMillis() / 100.0) / 10.0,
                vectorStoreStatus.getMessage());

        return ResponseEntity.ok(new Readiness(searchable ? "up" : "degraded", kb));
    }

    /**
     * @param status up / degraded —— 只看"检索是否可用"，不代表存活
     */
    public record Readiness(String status, KnowledgeBase knowledgeBase) {
    }

    /**
     * @param state       UNKNOWN / BUILDING / READY_FROM_CACHE / READY_BUILT / EMPTY / FAILED
     * @param searchable  为 false 时，所有 RAG 检索都会返回空，回答质量会明显下降
     * @param chunks      已索引的片段数
     * @param buildSeconds 构建耗时（秒）；命中缓存时接近 0
     * @param message     排障线索（缓存文件名 / 失败根因）
     */
    public record KnowledgeBase(String state, boolean searchable, int chunks,
                                double buildSeconds, String message) {
    }
}
