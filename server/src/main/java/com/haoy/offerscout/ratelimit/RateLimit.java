package com.haoy.offerscout.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流标记。
 * <p>
 * 加在 Controller 方法上即可生效，具体拦截逻辑见 {@link RateLimitInterceptor}。
 * <p>
 * <b>为什么需要它？</b>这个项目的每个接口背后都是真金白银的大模型调用：一次普通对话是一次 LLM 请求，
 * 一次 Agent 任务要跑十几步、每步一次 LLM 请求，RAG 还会额外调 embedding（按量计费）。
 * 没有限流，一个循环脚本就能把额度刷干 —— 所以限流在这里不是"锦上添花"，是保护成本的必需品。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 时间窗口内允许通过的请求次数上限 */
    int limit() default 20;

    /** 时间窗口长度，单位：秒 */
    int windowSeconds() default 60;

    /**
     * 限流维度的统计口径。
     */
    Dimension dimension() default Dimension.IP;

    /** 自定义超限提示语；留空则用默认文案（会带上剩余等待秒数） */
    String message() default "";

    enum Dimension {
        /**
         * 按客户端 IP 各算一份 —— 匿名接口（注册 / 登录）只能用这个，
         * 因为那时还没有身份可依据。
         */
        IP,
        /**
         * 按登录用户各算一份 —— <b>登录接口的正确口径</b>。
         * <p>
         * 用 IP 统计登录接口有两个别扭的地方：同一出口 NAT（校园网、公司网）下所有用户
         * 共享一份额度，会互相挤；换个代理又立刻重置额度，"每人 3 次"根本约束不到人。
         * 按 userId 统计才和业务主体对齐。
         * <p>
         * 前提：鉴权拦截器必须排在限流之前（{@code WebMvcConfig} 里已经是这个顺序），
         * 否则读到的 {@code UserContext} 是空的。真读不到时会回落到 IP，不会 NPE。
         */
        USER,
        /** 全局共用一个计数器 —— 用于保护某个特别昂贵的接口总量 */
        GLOBAL
    }
}
