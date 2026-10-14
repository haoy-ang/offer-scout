package com.haoy.offerscout.ratelimit;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 固定窗口计数的限流拦截器。
 * <p>
 * <b>为什么用拦截器而不是 AOP？</b>限流是 HTTP 层的事：判断维度需要客户端 IP、拒绝时要直接写响应状态码和响应体。
 * 拦截器的 {@code preHandle} 天然拿得到 {@code HttpServletRequest/Response}，比切面里用
 * {@code RequestContextHolder} 绕一圈拿请求更直接。切面更适合"与方法参数/返回值相关"的逻辑。
 * <p>
 * <b>为什么计数要用 Lua？</b>最直觉的写法是 {@code INCR} 后再 {@code EXPIRE}，但这是两条独立命令：
 * 如果 INCR 成功、EXPIRE 还没来得及执行（进程挂了 / 网络断了），这个 key 就永远不会过期，
 * 用户会被**永久限流** —— 这是限流实现里最经典的 bug。把两条命令写进一个 Lua 脚本，
 * Redis 会把整个脚本当一条命令原子执行，要么都生效要么都不生效。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /** 固定窗口计数：第一次访问时设置过期时间，后续只自增 */
    private static final String COUNT_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if tonumber(current) == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """;

    private static final String KEY_PREFIX = "offerscout:ratelimit:";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> countScript;

    public RateLimitInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.countScript = new DefaultRedisScript<>(COUNT_SCRIPT, Long.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        // 只有落到具体方法上的请求才谈得上限流（静态资源、预检请求等直接放行）
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;
        }

        String key = buildKey(request, handlerMethod, rateLimit);

        Long current;
        try {
            current = redisTemplate.execute(countScript, List.of(key),
                    String.valueOf(rateLimit.windowSeconds()));
        } catch (Exception e) {
            // 关键取舍：Redis 挂了要放行，不能把业务一起拖死。
            // 限流是「保护措施」，保护措施自己出故障时，正确选择是降级为不限流并报警，而不是让全站不可用。
            log.warn("限流计数失败，本次放行（请检查 Redis）：key={}", key, e);
            return true;
        }

        if (current == null) {
            return true;
        }
        if (current > rateLimit.limit()) {
            reject(response, rateLimit, remainingSeconds(key));
            log.warn("触发限流：key={} 第 {} 次请求，上限 {}/{}s", key, current,
                    rateLimit.limit(), rateLimit.windowSeconds());
            return false;
        }
        return true;
    }

    /** 组装限流 key：方法标识 + 统计维度 */
    private String buildKey(HttpServletRequest request, HandlerMethod handlerMethod, RateLimit rateLimit) {
        String dimension = rateLimit.dimension() == RateLimit.Dimension.IP
                ? "ip:" + clientIp(request)
                : "global";
        String method = handlerMethod.getBeanType().getSimpleName() + "." + handlerMethod.getMethod().getName();
        return KEY_PREFIX + method + ":" + dimension;
    }

    /**
     * 取客户端真实 IP。
     * <p>
     * 上线后请求通常先过 Nginx，{@code getRemoteAddr()} 拿到的是 Nginx 的地址，
     * 所有人的限流会算到同一个 key 上 —— 所以优先读代理链头部的 {@code X-Forwarded-For}。
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwarded)) {
            // 格式：client, proxy1, proxy2 —— 第一个才是真实客户端
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StrUtil.isNotBlank(realIp) ? realIp.trim() : request.getRemoteAddr();
    }

    /** 当前窗口还剩多少秒 —— 用来告诉客户端「多久之后可以重试」 */
    private long remainingSeconds(String key) {
        try {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            return ttl == null || ttl < 0 ? 1 : Math.max(ttl, 1);
        } catch (Exception e) {
            return 1;
        }
    }

    /** 超限：返回 429 + 中文提示 + Retry-After 头 */
    private void reject(HttpServletResponse response, RateLimit rateLimit, long retryAfterSeconds)
            throws IOException {
        String message = StrUtil.isBlank(rateLimit.message())
                ? "请求太频繁了，请 " + retryAfterSeconds + " 秒后再试。"
                : rateLimit.message();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 429);
        body.put("message", message);
        body.put("retryAfter", retryAfterSeconds);

        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.getWriter().write(JSONUtil.toJsonStr(body));
    }
}
