package com.haoy.offerscout.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StructuredOutputRetry} 的单元测试。
 * <p>
 * 不启 Spring 上下文、不调大模型 —— 重试逻辑本来就是纯函数，用手写的 Supplier 就能完整覆盖，
 * 也就是"能手动 new 就不启容器"。这样这些用例毫秒级跑完，且不花一分钱额度。
 * <p>
 * 覆盖的是几件必须成立的事：成功不重试、偶发失败能被吃掉、连续失败要抛业务异常、
 * 以及"能反序列化但内容残缺"也要触发重试（这是最容易被漏掉的一条）。
 */
class StructuredOutputRetryTest {

    /** 只要求结果不为 null 的宽松校验 */
    private static final Predicate<String> ANY = s -> true;

    @Test
    @DisplayName("一次就成功时不应重试")
    void shouldNotRetryWhenFirstAttemptSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        String result = StructuredOutputRetry.call("测试", () -> {
            calls.incrementAndGet();
            return "ok";
        }, ANY);

        assertEquals("ok", result);
        assertEquals(1, calls.get(), "首次成功还多调了一次，说明重试条件写错了");
    }

    @Test
    @DisplayName("首次抛异常、第二次成功：应重试并返回成功结果")
    void shouldRetryAfterExceptionThenSucceed() {
        AtomicInteger calls = new AtomicInteger();

        String result = StructuredOutputRetry.call("测试", () -> {
            if (calls.incrementAndGet() == 1) {
                // 模拟模型返回非法 JSON 导致反序列化失败
                throw new IllegalStateException("JSON 解析失败");
            }
            return "recovered";
        }, ANY);

        assertEquals("recovered", result);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("结果能反序列化但内容残缺时，也应触发重试")
    void shouldRetryWhenResultIsValidatedAsUnusable() {
        AtomicInteger calls = new AtomicInteger();

        // 第一次返回空字符串（模拟 dimensions 为空这种"静默成功"），第二次返回正常内容
        String result = StructuredOutputRetry.call("测试",
                () -> calls.incrementAndGet() == 1 ? "" : "full-content",
                s -> !s.isEmpty());

        assertEquals("full-content", result);
        assertEquals(2, calls.get(), "内容残缺没有触发重试 —— 空报告会被静默返回给用户");
    }

    @Test
    @DisplayName("返回 null 视为不可用，同样重试")
    void shouldTreatNullAsUnusable() {
        AtomicInteger calls = new AtomicInteger();

        String result = StructuredOutputRetry.call("测试",
                () -> calls.incrementAndGet() == 1 ? null : "value",
                ANY);

        assertEquals("value", result);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("连续失败达到上限后，抛业务异常且调用次数被限制在上限内")
    void shouldThrowAfterExhaustingAttempts() {
        AtomicInteger calls = new AtomicInteger();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                StructuredOutputRetry.call("简历诊断", () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("一直失败");
                }, ANY));

        assertEquals(3, calls.get(), "调用次数必须被上限卡住 —— 每次重试都是要花钱的模型调用");
        assertTrue(ex.getMessage().contains("简历诊断"), "报错文案应带上场景名，方便定位是哪个功能");
        assertTrue(ex.getMessage().contains("3"), "报错文案应说明尝试了几次");
    }

    @Test
    @DisplayName("连续返回不可用结果同样会走到上限并抛异常")
    void shouldThrowWhenAlwaysUnusable() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(BusinessException.class, () ->
                StructuredOutputRetry.call("岗位匹配", () -> {
                    calls.incrementAndGet();
                    return "";
                }, s -> !s.isEmpty()));

        assertEquals(3, calls.get());
    }

    @Test
    @DisplayName("Supplier 抛出的异常不应原样冒出去，而是包装成业务异常")
    void shouldWrapOriginalException() {
        Supplier<String> boom = () -> {
            throw new IllegalArgumentException("底层解析异常");
        };

        BusinessException ex = assertThrows(BusinessException.class,
                () -> StructuredOutputRetry.call("测试", boom, ANY));

        // 关键点：不能让底层的 IllegalArgumentException 直接冲到全局异常处理，
        // 那样用户看到的是 500 + 英文堆栈，而不是一句可读的中文提示。
        assertTrue(ex.getMessage().contains("可用"), "应转换成面向用户的提示");
    }
}
