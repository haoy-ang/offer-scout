package com.haoy.offerscout.controller;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.agent.OfferScoutAgent;
import com.haoy.offerscout.auth.RequireLogin;
import com.haoy.offerscout.ratelimit.RateLimit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 求职规划 Agent 接口。
 * <p>
 * 注入 {@link ObjectProvider} 而不是直接注入 {@link OfferScoutAgent}：
 * 因为 Agent 是 prototype 作用域，每次都取一个新实例，避免并发请求共享同一份可变状态。
 * <p>
 * <b>这里限流比其他接口紧得多（3 次/分钟）</b>：普通对话一次调用 = 一次大模型请求，
 * 而 Agent 一个任务要跑 3~8 步，每步都是一次完整的 LLM 请求（还带工具调用往返）。
 * 也就是说一次 Agent 调用的成本约等于十次普通对话 —— 限流额度必须按调用成本来定，
 * 而不是所有接口一刀切。
 * <p>
 * 另外打了 {@code @RequireLogin}：这个接口不仅是全场最贵的，而且是**唯一一个会把结果
 * 跑在异步线程上的**。匿名用户花别人的 token 跑 Agent，属于典型的资源滥用入口。
 */
@RestController
@RequestMapping("/ai/agent")
@RequireLogin
@Slf4j
public class OfferScoutAgentController {

    private final ObjectProvider<OfferScoutAgent> agentProvider;

    public OfferScoutAgentController(ObjectProvider<OfferScoutAgent> agentProvider) {
        this.agentProvider = agentProvider;
    }

    /**
     * 同步执行：等 Agent 跑完整个循环再一次性返回最终结论。
     * 适合接口调试；Agent 可能跑好几步，请给足超时时间。
     */
    @GetMapping("/run")
    @RateLimit(limit = 3, windowSeconds = 60, message = "Agent 任务比较耗资源，每分钟最多发起 3 次，请稍后再试。")
    public String run(@RequestParam String message) {
        if (StrUtil.isBlank(message)) {
            return "任务描述不能为空";
        }
        OfferScoutAgent agent = agentProvider.getObject();
        long start = System.currentTimeMillis();
        String answer = agent.run(message);
        log.info("Agent 任务结束，共 {} 步，耗时 {} 秒", agent.getCurrentStep(),
                (System.currentTimeMillis() - start) / 1000.0);
        return answer;
    }

    /**
     * 流式执行：先把每一步的「思考 / 工具调用」推给前端，最后推最终结论。
     * 这样用户不用干等几十秒，能看到 Agent 正在干什么。
     * <p>
     * <b>鉴权必须发生在 {@code CompletableFuture.runAsync} 之前</b>：
     * 拦截器和 {@code UserContext} 都基于 ThreadLocal，
     * 一旦进了异步线程，那里读不到任何身份信息。
     * 好在 {@code @RequireLogin} 是在 {@code preHandle} 阶段就拦下未登录请求的，
     * 而 preHandle 跑在请求线程上 —— 校验已经完成，异步里不需要再判一次。
     */
    @GetMapping(value = "/run/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limit = 3, windowSeconds = 60, message = "Agent 任务比较耗资源，每分钟最多发起 3 次，请稍后再试。")
    public SseEmitter runSse(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter(300_000L);
        OfferScoutAgent agent = agentProvider.getObject();

        // 超时 / 出错都等于"这次结果没人要了"：顺手叫停 Agent，别让它在后台白烧 token
        emitter.onTimeout(agent::abort);
        emitter.onError(e -> {
            log.debug("SSE 连接出错，客户端可能已断开", e);
            agent.abort();
        });

        agent.setStepListener(step -> {
            try {
                emitter.send(step);
            } catch (IOException e) {
                // 前端断开是常态（切页 / 关标签 / 点停止），不是故障 —— 但必须让 Agent 停下来，
                // 否则它会一步步把剩下的步骤跑完，全程没有人接收结果。
                log.debug("SSE 推送失败，客户端可能已断开，通知 Agent 中止", e);
                agent.abort();
            }
        });

        CompletableFuture.runAsync(() -> {
            try {
                // Agent 循环是阻塞的，必须放在独立线程里跑，否则会占住请求线程
                emitter.send(agent.run(message));
                emitter.complete();
            } catch (IOException e) {
                // 客户端断开时这里必然抛：AsyncRequestNotUsableException 就是 IOException 的子类，
                // 语义是"响应已经不可用了"。这属于正常现象（结果没人接收），于是：
                //   ① 不打 ERROR —— 否则日志里堆一堆看着吓人、其实无事发生的假故障；
                //   ② 不 completeWithError —— 那是在已经废掉的响应上再写一次，
                //      异常会二次抛给容器，同一个原因在日志里刷两遍栈（实测确认过）。
                log.debug("SSE 客户端已断开，丢弃最终结果", e);
                completeQuietly(emitter);
            } catch (Exception e) {
                log.error("Agent 流式执行失败", e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                    // 响应可能已不可用，二次失败没有补救手段，忽略
                }
            }
        });

        return emitter;
    }

    /** 收尾一个可能已经不可用的 emitter：失败就吞掉，别让它再惊动上层 */
    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开时 complete() 也可能抛（响应状态已废），这里没有补救手段，忽略
        }
    }
}
