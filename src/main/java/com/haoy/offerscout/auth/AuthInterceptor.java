package com.haoy.offerscout.auth;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录鉴权拦截器。
 * <p>
 * 只做两件事，各司其职：
 * <ol>
 *   <li><b>解析身份</b>：请求头里带 {@code Authorization: Bearer <token>} 就校验并放进 {@link UserContext}。
 *       <b>没有 token 也不报错</b> —— 因为有些接口本来就允许匿名访问（比如登录、健康检查）。</li>
 *   <li><b>强制登录</b>：只有打了 {@link RequireLogin} 的接口，才在"没解析出身份"时返回 401。</li>
 * </ol>
 * <p>
 * <b>为什么用拦截器而不是 Servlet Filter？</b>拦截器能拿到 {@link HandlerMethod}，
 * 从而知道"这次请求要落到哪个方法上"，才有办法按注解判断要不要强制登录。
 * 过滤器工作在更外层，那时还没做路由匹配，拿不到方法信息，只能靠 URL 白名单，容易漏。
 * <p>
 * <b>为什么不用整套 Spring Security？</b>Spring Security 的强项是"过滤器链 + 方法级鉴权 +
 * 一整套默认防护（CSRF、会话固定、安全响应头、并发会话控制）"，生产项目应该用它。
 * 这里手写是因为：本项目接口少、无表单提交（CSRF 不适用）、无 Session（会话固定不适用），
 * 引整套框架要额外处理 SecurityFilterChain 顺序、默认 403 而非 401、Swagger 放行等一堆细节，
 * 调试成本高于收益。**每一行都自己写的价值是：JWT 鉴权流程能被完整讲清楚。**
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenBlacklist tokenBlacklist;

    public AuthInterceptor(JwtService jwtService, TokenBlacklist tokenBlacklist) {
        this.jwtService = jwtService;
        this.tokenBlacklist = tokenBlacklist;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        resolveIdentity(request);

        if (!isLoginRequired(handler)) {
            return true;
        }
        if (UserContext.isLoggedIn()) {
            return true;
        }
        reject(response);
        return false;
    }

    /**
     * 🚨 请求结束必须清理 ThreadLocal。
     * <p>
     * Tomcat 用线程池处理请求，线程会被复用。不清理的话，下一个请求会读到上一个用户的 ID ——
     * 这是 ThreadLocal 最经典的越权漏洞。放在 {@code afterCompletion} 里能保证
     * 无论请求正常结束还是抛异常都会执行。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        UserContext.clear();
    }

    /** 有 token 就解析并放入上下文；没有或无效则保持"匿名"，不在这里报错 */
    private void resolveIdentity(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (StrUtil.isBlank(header) || !header.startsWith(BEARER_PREFIX)) {
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();

        jwtService.parse(token).ifPresent(payload -> {
            if (tokenBlacklist.isBlacklisted(payload.jti())) {
                // 已登出的 token：当作匿名，后续按接口是否要求登录决定放不放行
                return;
            }
            UserContext.set(new UserContext.Principal(
                    payload.userId(), payload.jti(), payload.expiresAt()));
        });
    }

    /** 方法或类上任一位置标了 @RequireLogin 就需要登录 */
    private boolean isLoginRequired(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        return handlerMethod.hasMethodAnnotation(RequireLogin.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), RequireLogin.class);
    }

    /**
     * 返回 401。
     * <p>
     * 用 401（Unauthorized，未认证）而不是 403（Forbidden，已认证但无权限）：
     * 语义上前者表示"你还没证明你是谁"，后者表示"我知道你是谁，但你不够格"。
     * 前端靠这个区分处理：401 → 跳登录页；403 → 提示无权限。
     */
    private void reject(HttpServletResponse response) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 401);
        body.put("message", "请先登录");

        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(body));
    }
}
