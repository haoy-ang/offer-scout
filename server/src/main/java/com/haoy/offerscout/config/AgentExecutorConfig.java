package com.haoy.offerscout.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 异步任务的专用线程池。
 * <p>
 * <b>为什么必须有这个类？</b>之前 {@code runSse} 里写的是
 * {@code CompletableFuture.runAsync(...)}，没传 executor —— 落到
 * {@link java.util.concurrent.ForkJoinPool#commonPool()}。那是 JVM 里
 * <b>全局共享</b>的公共池，并行度默认只有「CPU 核数 − 1」，而 Agent 任务是
 * <b>阻塞型长任务</b>（几十秒到几分钟）。更糟的是：无 executor 的普通 {@code Runnable}
 * 不会走 {@code ManagedBlocker} 的补偿扩线程机制，所以线程数固定不变 ——
 * 几个并发任务就能把它占死，连带 {@code parallelStream()} 和所有默认异步任务一起饿死，
 * 而这一切在日志里毫无痕迹。
 * <p>
 * <b>为什么用有界队列 + AbortPolicy？</b>拒绝策略是这里的核心设计：
 * <ul>
 *   <li>无界队列（{@code LinkedBlockingQueue} 默认）会把 OOM 风险藏起来 ——
 *       任务堆在队列里不报错，直到内存爆掉，届时连日志都写不出来。</li>
 *   <li>{@code CallerRunsPolicy} 看着温柔，实际是把任务甩回 Tomcat 请求线程执行 ——
 *       等于用一个阻塞几分钟的任务占住 Web 容器线程，比直接拒绝更危险。</li>
 *   <li>{@code AbortPolicy} + 调用方捕获 → 立刻返回 429「任务太多请稍后」，
 *       用户拿到确定的信息，系统保住可用性。这是唯一诚实的做法。</li>
 * </ul>
 * <p>
 * <b>容量怎么估？</b>限流是 3 次/分钟/IP，单任务耗时按 60 秒估，则单个活跃用户最多
 * 同时占 3 个线程、稳态平均占 0~1 个。核心 4 / 最大 8 / 队列 32 大致能兜住
 * 十几个活跃用户的突发，超过就被拒绝 —— 这个数只能按真实并发调，不是拍出来的最优解。
 * <p>
 * 线程名统一 {@code agent-} 前缀：线上出问题时，{@code jstack} 里一眼能把 Agent 任务
 * 和 Tomcat 线程（{@code http-nio-*}）分开，否则根本看不出哪堆线程在阻塞。
 */
@Configuration
@Slf4j
public class AgentExecutorConfig {

    /** Bean 名。注入时用 {@code @Qualifier} 指过来，避免和 Spring 默认的 taskExecutor 撞车 */
    public static final String AGENT_EXECUTOR = "agentExecutor";

    @Bean(name = AGENT_EXECUTOR)
    public ThreadPoolTaskExecutor agentExecutor(
            @Value("${offer-scout.agent.executor.core-size:4}") int coreSize,
            @Value("${offer-scout.agent.executor.max-size:8}") int maxSize,
            @Value("${offer-scout.agent.executor.queue-capacity:32}") int queueCapacity,
            @Value("${offer-scout.agent.executor.keep-alive-seconds:60}") int keepAliveSeconds) {

        if (maxSize < coreSize) {
            // 配错了直接启动失败，比运行期莫名其妙"线程池不干活"好排查
            throw new IllegalArgumentException(
                    "线程池配置错误：max-size(" + maxSize + ") 不能小于 core-size(" + coreSize + ")");
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("agent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 停机时给在跑的任务留收尾时间：Agent 可能正在往 SSE 推最后一段结论，
        // 直接掐掉的话前端看到的是"流突然断了"，比明确失败更难理解
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        log.info("Agent 线程池初始化：core={} max={} queue={} 拒绝策略=Abort",
                coreSize, maxSize, queueCapacity);
        return executor;
    }
}
