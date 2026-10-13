package com.haoy.offerscout.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「必须登录才能访问」的接口。
 * <p>
 * 可以打在方法上，也可以打在 Controller 类上（整个类一起生效）。
 * <p>
 * <b>为什么用注解而不是在 WebMvcConfig 里配路径白名单？</b>
 * 白名单是"默认放行，列表里的拦掉"—— 新增接口时忘了加进列表，接口就直接裸奔了。
 * 注解是"默认放行，标了才拦"，看起来一样，但配合「安全接口一律显式标注」的约定，
 * code review 时能一眼扫出哪个接口忘了标。这就是 Spring Security 那句
 * 「默认拒绝」的设计哲学 —— 这里的取舍是我更愿意用注解表达意图。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireLogin {
}
