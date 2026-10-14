package com.haoy.offerscout.controller;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.app.JobApp;
import com.haoy.offerscout.auth.RequireLogin;
import com.haoy.offerscout.auth.UserContext;
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
 * <p>
 * 类上打了 {@code @RequireLogin}：对话要写进"属于某个人"的会话里，匿名调用根本无处安放。
 * 打在整个类上而不是逐个方法上，是为了避免"新加接口忘了标注"。
 */
@RestController
@RequestMapping("/ai/job")
@RequireLogin
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
        return jobApp.doChat(message, resolveChatId(chatId), currentUserId());
    }

    /**
     * SSE 流式对话：边生成边推送，前端可以逐字渲染。
     */
    @GetMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limit = 20, windowSeconds = 60)
    public Flux<String> chatSse(@RequestParam String message,
                                @RequestParam(required = false) String chatId) {
        return jobApp.doChatByStream(message, resolveChatId(chatId), currentUserId());
    }

    /**
     * 带知识库（RAG）的对话：先检索本地资料，再基于资料回答。
     * 问面试题、简历写法这类有标准答案的问题时用这个接口。
     */
    @GetMapping("/chat/rag")
    @RateLimit(limit = 10, windowSeconds = 60)
    public String chatWithRag(@RequestParam String message,
                              @RequestParam(required = false) String chatId) {
        return jobApp.doChatWithRag(message, resolveChatId(chatId), currentUserId());
    }

    /**
     * RAG 的流式版本，前端用它做打字机效果。
     */
    @GetMapping(value = "/chat/rag/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limit = 10, windowSeconds = 60)
    public Flux<String> chatWithRagSse(@RequestParam String message,
                                       @RequestParam(required = false) String chatId) {
        return jobApp.doChatWithRagByStream(message, resolveChatId(chatId), currentUserId());
    }

    /**
     * 没传会话 ID 就新建一个，保证每次调用都有独立上下文。
     * <p>
     * 用 UUID 而不是自增 ID 是有意的：会话 ID 会出现在 URL 里，
     * 自增 ID 等于把"系统里一共有多少会话、别人是第几个"顺手告诉了所有人，
     * 也让"换个数字试试"成为一种有效的攻击手法（遍历越权）。
     */
    private String resolveChatId(String chatId) {
        return StrUtil.isBlank(chatId) ? UUID.randomUUID().toString() : chatId;
    }

    /**
     * 取当前登录用户。
     * <p>
     * 这里调用 {@code requireUserId()} 而不是直接读 ThreadLocal：
     * 类上已经有 {@code @RequireLogin}，身份理论上一定存在；
     * 但万一将来有人把注解删了或漏加，这里会**立刻抛 401 报错**，
     * 而不是静默地以"用户 0"的身份去读写数据。宁可炸，不要错。
     */
    private long currentUserId() {
        return UserContext.requireUserId();
    }
}
