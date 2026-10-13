package com.haoy.offerscout.controller;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.app.JobApp;
import com.haoy.offerscout.ratelimit.RateLimit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 求职助手对话接口。
 * <p>
 * 提供同步和 SSE 流式两种调用方式，前端默认用流式（打字机效果）。
 * <p>
 * 四个接口都挂了 {@code @RateLimit}：每次调用都是一次真实的大模型请求，不限流的话
 * 一个 for 循环就能把额度刷空 —— 限流在这里是成本保护，不是可有可无的装饰。
 * 带 RAG 的两个接口限得更紧，因为它们额外还要调一次 embedding（也是按量计费）。
 */
@RestController
@RequestMapping("/ai/job")
public class JobController {

    private final JobApp jobApp;

    public JobController(JobApp jobApp) {
        this.jobApp = jobApp;
    }

    /**
     * 同步对话：等模型生成完再一次返回。
     * 适合调试和短回答场景。
     */
    @GetMapping("/chat/sync")
    @RateLimit(limit = 20, windowSeconds = 60)
    public String chatSync(@RequestParam String message,
                           @RequestParam(required = false) String chatId) {
        return jobApp.doChat(message, resolveChatId(chatId));
    }

    /**
     * SSE 流式对话：边生成边推送，前端可以逐字渲染。
     */
    @GetMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limit = 20, windowSeconds = 60)
    public Flux<String> chatSse(@RequestParam String message,
                                @RequestParam(required = false) String chatId) {
        return jobApp.doChatByStream(message, resolveChatId(chatId));
    }

    /**
     * 带知识库（RAG）的对话：先检索本地资料，再基于资料回答。
     * 问面试题、简历写法这类有标准答案的问题时用这个接口。
     */
    @GetMapping("/chat/rag")
    @RateLimit(limit = 10, windowSeconds = 60)
    public String chatWithRag(@RequestParam String message,
                              @RequestParam(required = false) String chatId) {
        return jobApp.doChatWithRag(message, resolveChatId(chatId));
    }

    /**
     * RAG 的流式版本，前端用它做打字机效果。
     */
    @GetMapping(value = "/chat/rag/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limit = 10, windowSeconds = 60)
    public Flux<String> chatWithRagSse(@RequestParam String message,
                                       @RequestParam(required = false) String chatId) {
        return jobApp.doChatWithRagByStream(message, resolveChatId(chatId));
    }

    /**
     * 没传会话 ID 就新建一个，保证每次调用都有独立上下文。
     */
    private String resolveChatId(String chatId) {
        return StrUtil.isBlank(chatId) ? UUID.randomUUID().toString() : chatId;
    }
}
