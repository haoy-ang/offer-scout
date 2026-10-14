package com.haoy.offerscout.controller;

import com.haoy.offerscout.auth.RequireLogin;
import com.haoy.offerscout.auth.UserContext;
import com.haoy.offerscout.interview.InterviewReport;
import com.haoy.offerscout.interview.InterviewService;
import com.haoy.offerscout.interview.InterviewStart;
import com.haoy.offerscout.ratelimit.RateLimit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 模拟面试接口：开始 → 多轮追问 → 复盘。
 * <p>
 * 三者的关系由前端持有的 {@code sessionId} 串起来，后端不需要维护面试状态
 * （岗位方向和面试类型每轮都随请求带上），所以服务可以无状态水平扩容。
 * <p>
 * 限流按"调用成本 + 频率"分档：追问是高频轻量调用，额度给到 30/min；
 * 开始和复盘各是一次完整 LLM 调用，压到 5/min。
 */
@RestController
@RequestMapping("/ai/interview")
@RequireLogin
public class InterviewController {

    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    /**
     * 开始一场面试：返回会话 ID 与面试官的第一个问题。
     *
     * @param role 目标岗位（可选，默认按 Java 后端）
     * @param type 面试类型：tech / hr / mixed（默认 tech）
     */
    @PostMapping("/start")
    @RateLimit(limit = 5, windowSeconds = 60, message = "面试开启过于频繁，请稍后再试。")
    public InterviewStart start(@RequestParam(required = false) String role,
                                @RequestParam(required = false, defaultValue = "tech") String type) {
        return interviewService.start(role, type, UserContext.requireUserId());
    }

    /**
     * 候选人回答一轮，面试官追问（SSE 流式，边生成边推送）。
     */
    @GetMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limit = 30, windowSeconds = 60, message = "追问过于频繁，请稍后再试。")
    public Flux<String> chatSse(@RequestParam String sessionId,
                                @RequestParam String message,
                                @RequestParam(required = false) String role,
                                @RequestParam(required = false, defaultValue = "tech") String type) {
        return interviewService.chatByStream(sessionId, message, role, type, UserContext.requireUserId());
    }

    /**
     * 结束面试，生成结构化复盘报告。
     */
    @PostMapping("/report")
    @RateLimit(limit = 5, windowSeconds = 60, message = "复盘生成过于频繁，请稍后再试。")
    public InterviewReport report(@RequestParam String sessionId,
                                  @RequestParam(required = false) String role,
                                  @RequestParam(required = false, defaultValue = "tech") String type) {
        return interviewService.report(sessionId, role, type, UserContext.requireUserId());
    }
}
