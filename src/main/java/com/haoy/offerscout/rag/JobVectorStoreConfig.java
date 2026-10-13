package com.haoy.offerscout.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 向量库配置。
 * <p>
 * RAG 的第二步：把文档切成片段后，用 Embedding 模型把每段文本变成向量存起来。
 * <p>
 * 这里用的是 {@link SimpleVectorStore} —— 纯内存实现，重启即丢失。
 * 好处是零依赖、方便本地调试；缺点是不能持久化、无法支撑多实例部署。
 * 等业务跑通后可以无缝换成 PgVector / Milvus，代码层面只换这一个 Bean。
 */
@Configuration
@Slf4j
public class JobVectorStoreConfig {

    private final EmbeddingModel embeddingModel;
    private final JobDocumentLoader jobDocumentLoader;

    public JobVectorStoreConfig(EmbeddingModel embeddingModel, JobDocumentLoader jobDocumentLoader) {
        this.embeddingModel = embeddingModel;
        this.jobDocumentLoader = jobDocumentLoader;
    }

    /**
     * 向量库 Bean。
     * <p>
     * 启动时把 classpath:document/ 下的 Markdown 全部向量化写入。
     * 注意：这一步会真实调用 Embedding 接口，所以启动会比之前慢几秒。
     */
    @Bean
    public SimpleVectorStore jobVectorStore() {
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();

        List<Document> documents = jobDocumentLoader.loadMarkdowns();
        if (documents.isEmpty()) {
            log.warn("知识库为空（src/main/resources/document/ 下没有 .md 文档），RAG 检索不会命中任何内容");
            return vectorStore;
        }

        try {
            vectorStore.add(documents);
            log.info("向量库初始化完成：写入 {} 个文档片段", documents.size());
        } catch (Exception e) {
            // 刻意不抛出：知识库挂了不应该让整个应用起不来，只是 RAG 能力降级
            log.error("向量库初始化失败（重点排查：API Key 是否有效、网络是否可达、embedding 模型名是否正确）", e);
        }
        return vectorStore;
    }
}
