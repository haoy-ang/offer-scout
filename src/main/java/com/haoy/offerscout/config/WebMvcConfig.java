package com.haoy.offerscout.config;

import com.haoy.offerscout.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 扩展配置。
 * <p>
 * 目前只干一件事：挂上限流拦截器。用 {@code addPathPatterns("/**")} 全量挂载，
 * 是否限流由方法上有没有 {@code @RateLimit} 决定 —— 这样新增接口时不用回来改这里，
 * 避免了「改了配置忘了加路径，接口裸奔」这类问题。
 * <p>
 * 注意：这里的路径是**相对于 context-path 的**。本项目 context-path 是 {@code /api}，
 * 所以 {@code /**} 覆盖的是 {@code /api/**}，拦截器里拿到的 requestURI 已经带上了 {@code /api}。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/**");
    }
}
