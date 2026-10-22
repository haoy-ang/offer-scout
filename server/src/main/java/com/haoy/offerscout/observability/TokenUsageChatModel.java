package com.haoy.offerscout.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 给任意 {@link ChatModel} 套一层记账：统计每次调用的 token 与耗时。
 * <p>
 * <b>为什么包 ChatModel，而不是在 6 个 Service 里各加一行埋点？</b>
 * 项目里 6 处 {@code ChatClient.builder(chatModel)} 全部从同一个 ChatModel 出发，
 * 在<b>最底层包一次</b>就全覆盖了 —— 包括 Agent 内部每一轮 think/act 的调用，
 * 以及以后新写的任何调用。<b>埋点写在最高层会被漏，写在我这层漏不了。</b>
 * 这是"横切关注点放在最窄的必经之路"的典型应用，和拦截器管限流是同一个思路。
 * <p>
 * <b>两个必须处理的现实问题：</b>
 * <ol>
 *   <li><b>usage 可能是空的。</b>{@code getUsage()} 在部分供应商 / 部分场景下返回
 *       {@code EmptyUsage}（token 全为 0）而不是 null。所以判据是"总 token &gt; 0"，
 *       不能只判 null。取不到就记 {@code usagePresent=false} 并计数，
 *       让"统计不可信"本身也成为可见指标 —— 而不是假装记了 0 就当真的花 0。</li>
 *   <li><b>流式拿 usage 要靠最后一个 chunk。</b>OpenAI 兼容接口只在末尾 chunk 带 usage，
 *       中间 chunk 全是 0。所以这里用 {@code AtomicReference} 一路"记住最后一个非空 usage"，
 *       等 {@code doOnComplete} 再记一笔。如果供应商干脆不给，就只能记耗时 + 次数。</li>
 * </ol>
 * <b>计时用 {@code nanoTime} 而不是 {@code currentTimeMillis}：</b>后者受系统时间调整影响
 * （NTP 校时可能跳变，甚至算出负数耗时），而 nanoTime 是单调的，专门用来测间隔。
 */
@Slf4j
public class TokenUsageChatModel implements ChatModel {

    private final ChatModel delegate;
    private final TokenUsageRecorder recorder;

    public TokenUsageChatModel(ChatModel delegate, TokenUsageRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        // 场景必须在当前线程取 —— 这是真正发起调用的线程，能读到业务代码设置的值
        String scene = TokenUsageScene.current();
        long start = System.nanoTime();
        try {
            ChatResponse response = delegate.call(prompt);
            long costMillis = elapsedMillis(start);
            Usage usage = usageOf(response);
            recorder.record(scene, modelOf(response),
                    promptTokens(usage), completionTokens(usage), costMillis, false, hasUsage(usage));
            return response;
        } catch (RuntimeException e) {
            // 失败也记账：失败前输入 token 往往已经算过费（甚至输出了半截）。
            // 不记的话成本统计会系统性偏低，而"低估成本"比"多算一次"危险得多。
            recorder.record(scene, "unknown", 0, 0, elapsedMillis(start), false, false);
            throw e;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // ⚠️ 这一行在「调用方线程」执行（订阅前），所以能拿到业务设置的场景；
        // 下面的 doOnNext / doOnComplete 跑在 Reactor 线程，那时再读 ThreadLocal 就是 null 了
        String scene = TokenUsageScene.current();
        long start = System.nanoTime();

        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AtomicReference<String> lastModel = new AtomicReference<>();
        // doOnComplete 和 doOnError 在异常路径下可能都触发，用这个防重复记账
        AtomicBoolean recorded = new AtomicBoolean(false);

        return delegate.stream(prompt)
                .doOnNext(response -> {
                    Usage usage = usageOf(response);
                    // 只保留最后一个"真的有数"的 usage：末尾 chunk 才带总量，中间都是 0
                    if (hasUsage(usage)) {
                        lastUsage.set(usage);
                    }
                    String model = modelOf(response);
                    if (model != null && !model.isBlank()) {
                        lastModel.set(model);
                    }
                })
                .doOnComplete(() -> {
                    if (recorded.compareAndSet(false, true)) {
                        Usage usage = lastUsage.get();
                        recorder.record(scene, lastModel.get(),
                                promptTokens(usage), completionTokens(usage),
                                elapsedMillis(start), true, hasUsage(usage));
                    }
                })
                .doOnError(e -> {
                    if (recorded.compareAndSet(false, true)) {
                        recorder.record(scene, lastModel.get(), 0, 0, elapsedMillis(start), true, false);
                    }
                });
    }

    /**
     * 委托给真实模型。
     * <p>
     * <b>这个必须覆盖，不能省。</b>{@code getDefaultOptions()} 携带的是模型侧的默认参数
     * （温度、最大 token 等）。如果我们返回一个空的 ChatOptions，ChatClient 就再也读不到
     * 模型自身的默认配置了 —— 表现为"行为莫名其妙变了"，而且极难定位。
     */
    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    private static String modelOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getModel();
    }

    /** 有数才算有：EmptyUsage 的 token 全是 0，不能只判 null */
    private static boolean hasUsage(Usage usage) {
        if (usage == null) {
            return false;
        }
        Integer total = usage.getTotalTokens();
        return total != null && total > 0;
    }

    private static int promptTokens(Usage usage) {
        if (!hasUsage(usage) || usage.getPromptTokens() == null) {
            return 0;
        }
        return usage.getPromptTokens();
    }

    private static int completionTokens(Usage usage) {
        if (!hasUsage(usage) || usage.getCompletionTokens() == null) {
            return 0;
        }
        return usage.getCompletionTokens();
    }
}
