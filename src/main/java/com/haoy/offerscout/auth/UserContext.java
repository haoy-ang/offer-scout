package com.haoy.offerscout.auth;

import com.haoy.offerscout.common.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 当前登录用户（请求级）上下文。
 * <p>
 * <b>⚠️ 用 ThreadLocal 的代价必须记牢两点：</b>
 * <ol>
 *   <li><b>用完必须清理。</b>Tomcat 用线程池，线程会被复用。如果 {@code afterCompletion} 里不清理，
 *       下一个请求会「继承」上一个用户的身份 —— 这是 ThreadLocal 最经典的越权漏洞。
 *       清理动作在 {@link AuthInterceptor#afterCompletion} 里。</li>
 *   <li><b>换线程就丢。</b>SSE 接口的 {@code doOnComplete} 回调跑在 Reactor 线程上，
 *       那里读不到这里的值。所以**异步链路必须在请求线程上先把 userId 取出来、显式传参**，
 *       不能在回调里调 {@code requireUserId()}。</li>
 * </ol>
 */
public final class UserContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static Principal get() {
        return CURRENT.get();
    }

    public static Long getUserId() {
        Principal principal = CURRENT.get();
        return principal == null ? null : principal.userId();
    }

    /** 取当前用户 ID；未登录直接抛 401（给那些"必须登录"的业务代码用） */
    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    public static boolean isLoggedIn() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 当前身份。
     * <p>
     * 除了用户 ID，还留着 token 的 {@code jti} 和过期时间 ——
     * 因为登出时要把 jti 记进黑名单，而黑名单的 TTL 需要用到"这个 token 还剩多久过期"。
     * 一并存在这里，登出接口就不用再把 Authorization 头解析一遍。
     *
     * @param userId    用户 ID
     * @param jti       token 唯一标识
     * @param expiresAt token 过期时间戳（毫秒）
     */
    public record Principal(long userId, String jti, long expiresAt) {

        public long remainingSeconds() {
            return Math.max((expiresAt - System.currentTimeMillis()) / 1000, 0);
        }
    }
}
