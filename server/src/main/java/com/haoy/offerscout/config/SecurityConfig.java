package com.haoy.offerscout.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 安全相关的 Bean。
 * <p>
 * <b>为什么只引 {@code spring-security-crypto} 而不是整个 spring-boot-starter-security？</b>
 * 我们真正需要的只有 BCrypt 这一个工具类，但它躺在 Spring Security 的核心包里。
 * 而引入 starter 等于同时打开一整套自动配置：SecurityFilterChain 默认拦截所有请求、
 * 未登录返回 403 而不是 401、Swagger 和健康检查需要逐个放行、CSRF 要显式关掉……
 * 这个项目接口少、无表单提交（CSRF 不适用）、无 Session（会话固定攻击不适用），
 * 独自处理这些细节的成本高于收益，索性把鉴权流程按自己的理解写清楚（见 {@code auth} 包）。
 * <p>
 * <b>代价要认：</b>这意味着 CSRF 防护、安全响应头、并发会话控制这些 Spring Security
 * 默认帮你做掉的事，本项目是没有的。真上生产应该把整套框架引回来，
 * 而不是"手写了一个拦截器就当安全做完了"。
 */
@Configuration
public class SecurityConfig {

    /**
     * 密码编码器。
     * <p>
     * {@link BCryptPasswordEncoder} 默认强度 10 —— 也就是 2^10 次密钥扩展，单次校验约 50~100ms。
     * 这个"慢"是刻意设计：登录时慢 100ms 用户无感，但暴力破解的成本被抬高了好几个数量级。
     * 强度调高虽然更安全，但会成比例增加 CPU 开销，超过 12 之后收益开始不划算。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
