package com.haoy.offerscout.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link TokenUsageChatModel} 的单元测试。
 * <p>
 * 全部用假的 {@link ChatModel} 驱动，<b>不启 Spring、不调真实模型</b>：装饰器的逻辑是
 * "从响应里抠 usage 并交给记录器"，跟哪个供应商无关，没必要花额度去验证。
 * <p>
 * 覆盖的是容易写错的几件事：同步取数、流式只认末尾 chunk 的 usage、usage 缺失不能假装有、
 * 失败也要记账、以及 {@code getDefaultOptions} 必须委托（漏了会静默改变模型行为）。
 */
class TokenUsageChatModelTest {

    /** 造一个带 usage 的响应 */
    private static ChatResponse response(String text, String model, int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    /** usage 拿不到时的样子：token 全 0（EmptyUsage 的实际表现） */
    private static ChatResponse responseWithoutUsage(String text, String model) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(new DefaultUsage(0, 0, 0))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    /** 假模型：同步返回预置响应，流式按顺序吐预置响应 */
    static class FakeChatModel implements ChatModel {
        private final ChatResponse syncResponse;
        private final List<ChatResponse> streamResponses;
        private final ChatOptions defaultOptions = ChatOptions.builder().model("fake-default-model").build();
        private RuntimeException failWith;

        FakeChatModel(ChatResponse syncResponse, ChatResponse... streamResponses) {
            this.syncResponse = syncResponse;
            this.streamResponses = Arrays.asList(streamResponses);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (failWith != null) {
                throw failWith;
            }
            return syncResponse;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.fromIterable(streamResponses);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return defaultOptions;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sceneOf(Map<String, Object> snapshot, String scene) {
        Map<String, Object> byScene = (Map<String, Object>) snapshot.get("byScene");
        return (Map<String, Object>) byScene.get(scene);
    }

    private static long num(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).longValue();
    }

    @Test
    @DisplayName("同步调用：token / 场景 / 模型都被记下来")
    void recordsSyncCall() {
        TokenUsageRecorder recorder = new TokenUsageRecorder();
        TokenUsageChatModel model = new TokenUsageChatModel(
                new FakeChatModel(response("答案", "qwen-max", 120, 80)), recorder);

        try (var ignored = TokenUsageScene.open("智能问答")) {
            model.call(new Prompt("问题"));
        }

        Map<String, Object> snapshot = recorder.snapshot();
        assertEquals(1, num(snapshot, "totalCalls"));
        assertEquals(120, num(snapshot, "promptTokens"));
        assertEquals(80, num(snapshot, "completionTokens"));
        assertEquals(200, num(snapshot, "totalTokens"));
        assertEquals(0, num(snapshot, "usageMissingCalls"), "有 usage 却记成了缺失");

        Map<String, Object> scene = sceneOf(snapshot, "智能问答");
        assertEquals(1, num(scene, "calls"), "场景没记上 —— 事后就说不清哪个功能贵");
        assertEquals(200, num(scene, "totalTokens"));
    }

    @Test
    @DisplayName("usage 拿不到时如实标缺失，不能假装花了 0")
    void marksUsageMissingInsteadOfPretendingZero() {
        TokenUsageRecorder recorder = new TokenUsageRecorder();
        TokenUsageChatModel model = new TokenUsageChatModel(
                new FakeChatModel(responseWithoutUsage("答案", "qwen-max")), recorder);

        model.call(new Prompt("问题"));

        Map<String, Object> snapshot = recorder.snapshot();
        assertEquals(1, num(snapshot, "totalCalls"));
        assertEquals(0, num(snapshot, "totalTokens"));
        assertEquals(1, num(snapshot, "usageMissingCalls"),
                "usage 缺失没有被计数 —— 成本统计会静默偏低，而没人会发现");
    }

    @Test
    @DisplayName("流式：只认末尾 chunk 的 usage，且只记一次")
    void recordsStreamUsageFromLastChunkOnly() {
        TokenUsageRecorder recorder = new TokenUsageRecorder();
        // 真实流式就是这样：中间 chunk 的 usage 全是 0，只有最后一个带总量
        FakeChatModel delegate = new FakeChatModel(null,
                responseWithoutUsage("你", "qwen-plus"),
                responseWithoutUsage("好", "qwen-plus"),
                response("你好", "qwen-plus", 30, 10));
        TokenUsageChatModel model = new TokenUsageChatModel(delegate, recorder);

        try (var ignored = TokenUsageScene.open("知识库问答")) {
            model.stream(new Prompt("问题")).blockLast();
        }

        Map<String, Object> snapshot = recorder.snapshot();
        assertEquals(1, num(snapshot, "totalCalls"), "流式被记了多次 —— doOnComplete 与 doOnError 重复触发");
        assertEquals(40, num(snapshot, "totalTokens"));
        assertEquals(0, num(snapshot, "usageMissingCalls"));
    }

    @Test
    @DisplayName("流式全程没有 usage：记缺失，不能静默当成 0 花销")
    void marksUsageMissingWhenStreamHasNoUsage() {
        TokenUsageRecorder recorder = new TokenUsageRecorder();
        TokenUsageChatModel model = new TokenUsageChatModel(
                new FakeChatModel(null,
                        responseWithoutUsage("a", "qwen-plus"),
                        responseWithoutUsage("b", "qwen-plus")), recorder);

        model.stream(new Prompt("问题")).blockLast();

        Map<String, Object> snapshot = recorder.snapshot();
        assertEquals(1, num(snapshot, "totalCalls"));
        assertEquals(1, num(snapshot, "usageMissingCalls"));
    }

    @Test
    @DisplayName("调用失败也要记一笔：失败前输入 token 往往已经计费过")
    void recordsFailedCall() {
        TokenUsageRecorder recorder = new TokenUsageRecorder();
        FakeChatModel delegate = new FakeChatModel(response("不会用到", "qwen-max", 1, 1));
        delegate.failWith = new IllegalStateException("上游 429");
        TokenUsageChatModel model = new TokenUsageChatModel(delegate, recorder);

        try {
            model.call(new Prompt("问题"));
        } catch (IllegalStateException expected) {
            // 预期：异常原样抛出去，但账要记
        }

        Map<String, Object> snapshot = recorder.snapshot();
        assertEquals(1, num(snapshot, "totalCalls"), "失败的调用没记账 —— 成本会被系统性低估");
        assertEquals(1, num(snapshot, "usageMissingCalls"));
    }

    @Test
    @DisplayName("getDefaultOptions 必须委托给真实模型")
    void delegatesDefaultOptions() {
        FakeChatModel delegate = new FakeChatModel(response("x", "qwen-max", 1, 1));
        TokenUsageChatModel model = new TokenUsageChatModel(delegate, new TokenUsageRecorder());

        // 漏了委托的话，ChatClient 读不到模型侧默认温度/最大 token，行为会静默改变且极难定位
        assertSame(delegate.getDefaultOptions(), model.getDefaultOptions());
    }

    @Test
    @DisplayName("场景用完要还原，嵌套调用不能把外层场景冲掉")
    void sceneIsRestoredAfterClose() {
        assertNull(TokenUsageScene.current());

        try (var outer = TokenUsageScene.open("外层")) {
            assertEquals("外层", TokenUsageScene.current());
            try (var inner = TokenUsageScene.open("内层")) {
                assertEquals("内层", TokenUsageScene.current());
            }
            assertEquals("外层", TokenUsageScene.current(), "内层关闭后没还原外层场景");
        }
        // 不清理的话，Tomcat 复用的线程会把下一个请求记到上一个场景上
        assertNull(TokenUsageScene.current(), "ThreadLocal 没清理");
    }

    @Test
    @DisplayName("没设置场景时记为 unknown，不抛 NPE")
    void recordsUnknownSceneWhenNotSet() {
        TokenUsageRecorder recorder = new TokenUsageRecorder();
        TokenUsageChatModel model = new TokenUsageChatModel(
                new FakeChatModel(response("x", "qwen-max", 5, 5)), recorder);

        model.call(new Prompt("问题"));

        Map<String, Object> snapshot = recorder.snapshot();
        assertNotNull(sceneOf(snapshot, "unknown"), "未设置场景时应落到 unknown，而不是丢失这次调用");
    }
}
