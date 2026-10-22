package com.haoy.offerscout.ratelimit;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.haoy.offerscout.auth.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>
 * <b>⚠️ 关于客户端 IP 的一个安全前提：</b>{@code X-Forwarded-For} 是<b>客户端可以随便写</b>的请求头。
 * 原先的实现无条件优先读它 —— 意味着攻击者在头里随便编个 IP 就能绕过全部限流。
 * 现在默认<b>不信任</b>该头（{@code offer-scout.ratelimit.trust-forwarded-headers=false}），
 * 只认 TCP 连接的对端地址；只有确实部署在可信代理之后（且代理会覆盖该头）时才显式打开。
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

    /** 是否信任 {@code X-Forwarded-For}。默认 false —— 安全默认值 */
    private final boolean trustForwardedHeaders;

    @Autowired
    public RateLimitInterceptor(StringRedisTemplate redisTemplate,
                                @Value("${offer-scout.ratelimit.trust-forwarded-headers:false}")
                                boolean trustForwardedHeaders) {
        this.redisTemplate = redisTemplate;
        this.countScript = new DefaultRedisScript<>(COUNT_SCRIPT, Long.class);
        this.trustForwardedHeaders = trustForwardedHeaders;
        if (trustForwardedHeaders) {
            log.info("限流按 X-Forwarded-For 取客户端 IP —— 仅当前置代理会覆盖该头时才该开启");
        }
    }

    /**
     * 测试用构造器，等价于「不信任 X-Forwarded-For」。
     * <p>
     * 保留它有两个作用：让不启 Spring 上下文的单元测试能手工 new 出来；
     * 同时把"默认安全"写进签名 —— 想信任代理必须显式传 true。
     */
    public RateLimitInterceptor(StringRedisTemplate redisTemplate) {
        this(redisTemplate, false);
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
        String dimension = switch (rateLimit.dimension()) {
            case IP -> "ip:" + clientIp(request);
            case USER -> {
                Long userId = UserContext.getUserId();
                // 登录用户按 userId 统计；未登录（例如未经过 RequireLogin 保护）回落到 IP 防匿名刷
                yield userId != null ? "user:" + userId : "ip:" + clientIp(request);
            }
            case GLOBAL -> "global";
        };
        String method = handlerMethod.getBeanType().getSimpleName() + "." + handlerMethod.getMethod().getName();
        return KEY_PREFIX + method + ":" + dimension;
    }

    /**
     * 取客户端 IP（仅用于限流统计口径）。
     * <p>
     * <b>默认只认 TCP 连接的对端地址。</b>请求头是不可信输入 —— {@code X-Forwarded-For}
     * 客户端想填什么就填什么，无条件优先读它，等于把限流的开关交给攻击者。
     * <p>
     * 只有部署在可信代理之后时，才把 {@code offer-scout.ratelimit.trust-forwarded-headers}
     * 打开，并且要求代理用 <b>覆盖</b> 而非追加的方式写入该头：
     * <pre>proxy_set_header X-Forwarded-For $remote_addr;</pre>
     * 这样第一段才真的是客户端地址。如果用的是 {@code $proxy_add_x_forwarded_for}（追加），
     * 第一段仍然是客户端伪造的值，限流照样能被绕过。
     */
    private String clientIp(HttpServletRequest request) {
        if (!trustForwardedHeaders) {
            return request.getRemoteAddr();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwarded)) {
            // 格式：client, proxy1, proxy2 —— 前提是可信代理覆盖写入，第一个才是真实客户端
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
