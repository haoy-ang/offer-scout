package com.haoy.offerscout.config;

import com.haoy.offerscout.auth.AuthInterceptor;
import com.haoy.offerscout.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 扩展配置：挂载拦截器。
 * <p>
 * 两个拦截器都用 {@code addPathPatterns("/**")} 全量挂载，是否生效由方法上的注解决定
 * （{@code @RequireLogin} / {@code @RateLimit}）—— 这样新增接口时不用回来改这里，
 * 避免了「改了配置忘了加路径，接口裸奔」这类问题。
 * <p>
 * 注意：这里的路径是**相对于 context-path 的**。本项目 context-path 是 {@code /api}，
 * 所以 {@code /**} 覆盖的是 {@code /api/**}，拦截器里拿到的 requestURI 已经带上了 {@code /api}。
 * <p>
 * <b>顺序不是随意写的：先鉴权，后限流。</b>
 * <ul>
 *   <li><b>身份先行</b>：鉴权拦截器负责把 {@code UserContext} 填好，后续任何一层（包括未来的
 *       业务代码、审计日志）都能直接读到"当前是谁"。这与 Servlet 过滤器链里
 *       认证过滤器靠前的惯例一致。</li>
 *   <li><b>省掉无用开销</b>：签名不对 / 已过期的 token 在鉴权层就被拒绝（401），
 *       根本走不到限流器，也就不必为一次必然失败的请求去 Redis 记一次数。</li>
 *   <li><b>清理顺序天然正确</b>：拦截器的 {@code afterCompletion} 按注册的**逆序**执行，
 *       所以鉴权层最后清理 {@code UserContext} —— 限流器执行期间上下文仍然可读。</li>
 * </ul>
 * 反过来（限流在前）也有它的道理：限流是更便宜的外层防护，能更早兜住洪峰，
 * 也能顺带限制住鉴权层查 Redis 黑名单的次数。两种都能自圆其说，
 * 这里的取舍是"身份先建立、无效请求尽早短路"。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor, RateLimitInterceptor rateLimitInterceptor) {
        this.authInterceptor = authInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/**");
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/**");
    }
}
