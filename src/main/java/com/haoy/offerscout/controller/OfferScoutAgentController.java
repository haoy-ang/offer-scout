package com.haoy.offerscout.controller;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.agent.OfferScoutAgent;
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
 */
@RestController
@RequestMapping("/ai/agent")
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
     */
    @GetMapping(value = "/run/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runSse(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter(300_000L);
        OfferScoutAgent agent = agentProvider.getObject();

        agent.setStepListener(step -> {
            try {
                emitter.send(step);
            } catch (IOException e) {
                // 前端断开是常态，不必当成错误往上抛
                log.debug("SSE 推送失败，客户端可能已断开", e);
            }
        });

        CompletableFuture.runAsync(() -> {
            try {
                // Agent 循环是阻塞的，必须放在独立线程里跑，否则会占住请求线程
                emitter.send(agent.run(message));
                emitter.complete();
            } catch (Exception e) {
                log.error("Agent 流式执行失败", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
