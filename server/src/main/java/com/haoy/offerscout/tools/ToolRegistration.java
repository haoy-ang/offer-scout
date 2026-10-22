package com.haoy.offerscout.tools;

import com.haoy.offerscout.cache.RedisCacheService;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具集中注册。
 * <p>
 * {@code ToolCallbacks.from(...)} 会扫描传入对象里带 {@code @Tool} 注解的方法，
 * 每个方法变成一个可被大模型调用的工具。所以工具类的职责就是「把 Java 方法暴露成模型能力」，
 * 参数说明（@ToolParam）写得好不好，直接决定模型会不会用对工具、传对参数。
 */
@Configuration
public class ToolRegistration {

    /**
     * OfferScout 的完整工具集。
     * <p>
     * 注意这里返回的是 {@code ToolCallback[]} 而不是 {@code ToolCallbackProvider}：
     * {@code ToolCallAgent} 需要拿到具体数组，手动交给 {@code ToolCallingManager} 执行，
     * 而不是让 Spring AI 在调用 ChatClient 时自动执行。
     */
    @Bean
    public ToolCallback[] offerScoutTools(VectorStore jobVectorStore,
                                          RedisCacheService cacheService,
                                          @org.springframework.beans.factory.annotation.Value("${offer-scout.rag.similarity-threshold:0.55}")
                                          double similarityThreshold) {
        return ToolCallbacks.from(
                new JobKnowledgeTool(jobVectorStore, cacheService, similarityThreshold),
                new DateTimeTool(),
                new TerminateTool()
        );
    }
}
