package com.haoy.offerscout.config;

import com.haoy.offerscout.observability.TokenUsageChatModel;
import com.haoy.offerscout.observability.TokenUsageRecorder;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 把真实模型包进记账装饰器。
 * <p>
 * <b>装配方式为什么是 {@code @Primary} + {@code @Qualifier} 组合？</b>
 * 需要同时满足两件事：
 * <ol>
 *   <li>业务代码按 <b>类型</b> 注入 {@code ChatModel}（6 处都是这样写的，没有 {@code @Qualifier}），
 *       所以要有一个 {@code @Primary} 的 {@code ChatModel} 让它们默认拿到装饰后的版本；</li>
 *   <li>装饰器内部要拿到<b>原生</b>那个 {@code ChatModel} 来做委托 —— 但如果直接按类型注入，
 *       Spring 会挑 {@code @Primary}（也就是装饰器自己），直接构成自我依赖、启动报循环依赖。
 *       所以这里必须用 {@code @Qualifier("dashscopeChatModel")} 点死原生 bean 的名字。</li>
 * </ol>
 * 一句话：<b>{@code @Primary} 对外、{@code @Qualifier} 对内</b>。这个组合是"装饰器模式在 Spring 里
 * 落地"的标准写法，写错的表现是启动期循环依赖，而不是编译错误 —— 所以必须靠上下文测试兜住。
 */
@Configuration
public class TokenUsageConfig {

    @Bean
    @Primary
    public ChatModel tokenUsageChatModel(
            @Qualifier("dashscopeChatModel") ChatModel dashscopeChatModel,
            TokenUsageRecorder recorder) {
        return new TokenUsageChatModel(dashscopeChatModel, recorder);
    }
}
