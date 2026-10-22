package com.haoy.offerscout.ratelimit;

import com.haoy.offerscout.auth.UserContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流拦截器测试。
 * <p>
 * <b>刻意不启 Spring 上下文</b>：限流逻辑只依赖一个 Redis 连接，手工 new 出来即可。
 * 起完整上下文要加载向量库和 MySQL，为了测一个计数器并不划算（这也是这个项目里
 * 唯一不碰大模型的测试类，跑一次不到一秒）。
 * <p>
 * 前置条件：本机 Redis 在 6379 上运行（见 README 环境要求）。
 */
@Tag("requires-infra")
class RateLimitInterceptorTest {

    private static final String KEY_PATTERN = "offerscout:ratelimit:*";

    /** 只为提供"带注解的方法签名"而存在的样例控制器 */
    static class SampleController {

        @RateLimit(limit = 2, windowSeconds = 60)
        public String limited() {
            return "ok";
        }

        @RateLimit(limit = 1, windowSeconds = 60, message = "自定义提示语")
        public String customMessage() {
            return "ok";
        }

        /** 全局维度：不区分 IP，所有人共用一个计数器 */
        @RateLimit(limit = 5, windowSeconds = 60, dimension = RateLimit.Dimension.GLOBAL)
        public String globalLimit() {
            return "ok";
        }

        /** 没有注解 —— 应该完全不限流 */
        public String unlimited() {
            return "ok";
        }
    }

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RateLimitInterceptor interceptor;

    @BeforeAll
    static void initRedis() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        interceptor = new RateLimitInterceptor(redisTemplate);
    }

    @AfterAll
    static void closeRedis() {
        clearCounters();
        connectionFactory.destroy();
    }

    /** 每个用例前清掉计数器，否则用例之间会互相影响 */
    @BeforeEach
    void resetCounters() {
        clearCounters();
    }

    private static void clearCounters() {
        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private record CallResult(boolean passed, MockHttpServletResponse response) {
    }

    /** 模拟一次打到某个方法上的请求 */
    private static CallResult call(String methodName, String clientIp) throws Exception {
        Method method = SampleController.class.getMethod(methodName);
        HandlerMethod handlerMethod = new HandlerMethod(new SampleController(), method);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test/" + methodName);
        request.setRemoteAddr(clientIp);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(request, response, handlerMethod);
        return new CallResult(passed, response);
    }

    @Test
    @DisplayName("窗口内超过上限：前 N 次放行，第 N+1 次返回 429 且带 Retry-After")
    void rejectsWhenLimitExceeded() throws Exception {
        assertThat(call("limited", "10.0.0.1").passed()).isTrue();
        assertThat(call("limited", "10.0.0.1").passed()).isTrue();

        CallResult third = call("limited", "10.0.0.1");

        assertThat(third.passed()).isFalse();
        assertThat(third.response().getStatus()).isEqualTo(429);
        assertThat(third.response().getHeader("Retry-After")).isNotNull();
        assertThat(third.response().getContentAsString())
                .contains("\"code\":429")
                .contains("秒后再试");
    }

    @Test
    @DisplayName("限流按 IP 各算一份：一个 IP 被打满不影响另一个 IP")
    void countsPerClientIp() throws Exception {
        assertThat(call("limited", "10.0.0.1").passed()).isTrue();
        assertThat(call("limited", "10.0.0.1").passed()).isTrue();
        assertThat(call("limited", "10.0.0.1").passed()).isFalse();

        // 换个 IP，额度应该是全新的
        assertThat(call("limited", "10.0.0.2").passed()).isTrue();
    }

    @Test
    @DisplayName("全局维度：不同 IP 共用同一个计数器")
    void globalDimensionSharesCounter() throws Exception {
        for (int i = 0; i < 5; i++) {
            String ip = i % 2 == 0 ? "10.0.0.1" : "10.0.0.2";
            assertThat(call("globalLimit", ip).passed()).as("第 %d 次应放行", i + 1).isTrue();
        }
        // 第 6 次：额度在所有人之间共享，所以换个新 IP 也拿不到额度
        assertThat(call("globalLimit", "10.0.0.3").passed()).isFalse();
    }

    @Test
    @DisplayName("没有 @RateLimit 注解的方法不受限制")
    void ignoresMethodsWithoutAnnotation() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(call("unlimited", "10.0.0.9").passed()).isTrue();
        }
        assertThat(redisTemplate.keys(KEY_PATTERN)).isNullOrEmpty();
    }

    @Test
    @DisplayName("计数器落进 Redis 且带过期时间（不会永久占额度）")
    void counterHasTtl() throws Exception {
        call("limited", "10.0.0.1");

        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        assertThat(keys).isNotNull().hasSize(1);

        String key = keys.iterator().next();
        assertThat(key).contains("SampleController.limited").contains("ip:10.0.0.1");

        Long ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("自定义提示语会被原样返回")
    void usesCustomMessage() throws Exception {
        assertThat(call("customMessage", "10.0.0.1").passed()).isTrue();

        CallResult second = call("customMessage", "10.0.0.1");
        assertThat(second.passed()).isFalse();
        assertThat(second.response().getContentAsString()).contains("自定义提示语");
    }

    @Test
    @DisplayName("窗口过期后额度自动恢复")
    void quotaRecoversAfterWindow() throws Exception {
        // 用一个 1 秒的窗口，直接等它过期，避免为了测过期去改系统时间
        Method method = ShortWindowController.class.getMethod("shortWindow");
        HandlerMethod handlerMethod = new HandlerMethod(new ShortWindowController(), method);

        assertThat(fire(handlerMethod).passed()).isTrue();
        assertThat(fire(handlerMethod).passed()).isFalse();

        Thread.sleep(Duration.ofSeconds(2));

        assertThat(fire(handlerMethod).passed()).isTrue();
    }

    static class ShortWindowController {
        @RateLimit(limit = 1, windowSeconds = 1)
        public String shortWindow() {
            return "ok";
        }
    }

    private static CallResult fire(HandlerMethod handlerMethod) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test/short");
        request.setRemoteAddr("10.0.0.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean passed = interceptor.preHandle(request, response, handlerMethod);
        return new CallResult(passed, response);
    }

    /** 指定对端 IP 与伪造的 X-Forwarded-For，其余同上 */
    private static CallResult fireWith(HandlerMethod handlerMethod, String remoteAddr, String forwardedFor)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test/x");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean passed = interceptor.preHandle(request, response, handlerMethod);
        return new CallResult(passed, response);
    }

    /** 按 userId 计数的样例 */
    static class UserDimensionController {
        @RateLimit(limit = 2, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
        public String limited() {
            return "ok";
        }
    }

    @Test
    @DisplayName("USER 维度：额度跟着登录用户走，换 IP 也重置不了")
    void userDimensionCountsPerUserNotPerIp() throws Exception {
        Method method = UserDimensionController.class.getMethod("limited");
        HandlerMethod handlerMethod = new HandlerMethod(new UserDimensionController(), method);

        try {
            // 用户 1：三次请求故意来自三个不同 IP
            UserContext.set(new UserContext.Principal(1L, "jti-1", Long.MAX_VALUE));
            assertThat(fireWith(handlerMethod, "10.0.1.1", null).passed()).isTrue();
            assertThat(fireWith(handlerMethod, "10.0.1.2", null).passed()).isTrue();
            assertThat(fireWith(handlerMethod, "10.0.1.3", null).passed())
                    .as("同一用户换 IP 仍应受限 —— 否则换个代理就能无限刷")
                    .isFalse();

            // 用户 2：用同一个 IP，额度是全新的
            UserContext.set(new UserContext.Principal(2L, "jti-2", Long.MAX_VALUE));
            assertThat(fireWith(handlerMethod, "10.0.1.1", null).passed())
                    .as("同 IP 的不同用户不该共享额度")
                    .isTrue();
        } finally {
            UserContext.clear();
        }
    }

    @Test
    @DisplayName("默认不信任 X-Forwarded-For：伪造该头绕不过限流")
    void forgedForwardedHeaderCannotBypassRateLimit() throws Exception {
        Method method = SampleController.class.getMethod("limited");
        HandlerMethod handlerMethod = new HandlerMethod(new SampleController(), method);

        // 每次伪造一个不同的 XFF，但 TCP 对端地址不变 —— 应该被正常计数并拦住
        assertThat(fireWith(handlerMethod, "10.0.2.1", "203.0.113.1").passed()).isTrue();
        assertThat(fireWith(handlerMethod, "10.0.2.1", "203.0.113.2").passed()).isTrue();
        assertThat(fireWith(handlerMethod, "10.0.2.1", "203.0.113.3").passed())
                .as("X-Forwarded-For 被信任了 —— 客户端编个 IP 就能绕过全部限流")
                .isFalse();
    }
}
