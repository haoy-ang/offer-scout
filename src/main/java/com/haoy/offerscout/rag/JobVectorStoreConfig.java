package com.haoy.offerscout.rag;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

/**
 * 向量库配置。
 * <p>
 * RAG 的第二步：把文档切成片段后，用 Embedding 模型把每段文本变成向量存起来。
 * <p>
 * 这里用的是 {@link SimpleVectorStore} —— 纯内存实现。
 * 好处是零依赖、方便本地调试；缺点是重启即丢失、无法支撑多实例部署。
 * 等业务跑通后可以无缝换成 PgVector / Milvus，代码层面只换这一个 Bean。
 */
@Configuration
@Slf4j
public class JobVectorStoreConfig {

    /** 向量缓存目录，可用 offer-scout.vector-store.cache-dir 覆盖 */
    @Value("${offer-scout.vector-store.cache-dir:./.vector-cache}")
    private String cacheDir;

    /** embedding 模型名会参与缓存 key 计算：换了模型自动重建，不会读到旧维度的废数据 */
    @Value("${spring.ai.dashscope.embedding.options.model:unknown}")
    private String embeddingModelName;

    private final EmbeddingModel embeddingModel;
    private final JobDocumentLoader jobDocumentLoader;

    public JobVectorStoreConfig(EmbeddingModel embeddingModel, JobDocumentLoader jobDocumentLoader) {
        this.embeddingModel = embeddingModel;
        this.jobDocumentLoader = jobDocumentLoader;
    }

    /**
     * 向量库 Bean。
     * <p>
     * 为什么要有磁盘缓存？知识库上千个片段时，每次启动都重新调 Embedding 接口要几分钟、
     * 还得重复花钱。所以这里做两级：缓存命中直接 load，未命中才真正向量化并落盘。
     * <p>
     * 缓存 key = 全部文档内容 + 文件名 + embedding 模型名的摘要。
     * 只要改了任何一篇素材，key 就变，旧缓存自动失效 —— 不用手动删，也不会读到过期数据。
     */
    @Bean
    public SimpleVectorStore jobVectorStore() {
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();

        List<Document> documents = jobDocumentLoader.loadMarkdowns();
        if (documents.isEmpty()) {
            log.warn("知识库为空（src/main/resources/document/ 下没有 .md 文档），RAG 检索不会命中任何内容");
            return vectorStore;
        }

        File cacheFile = resolveCacheFile(documents);
        if (cacheFile.exists()) {
            try {
                vectorStore.load(cacheFile);
                log.info("向量库命中缓存：{}（{} 个片段），跳过重复向量化", cacheFile.getName(), documents.size());
                return vectorStore;
            } catch (Exception e) {
                log.warn("缓存文件损坏或格式不兼容，改为重新向量化：{}", cacheFile.getName(), e);
            }
        }

        try {
            long start = System.currentTimeMillis();
            vectorStore.add(documents);
            long cost = System.currentTimeMillis() - start;
            log.info("向量库初始化完成：写入 {} 个片段，耗时 {} 秒", documents.size(), cost / 1000.0);
            saveCache(vectorStore, cacheFile);
        } catch (Exception e) {
            // 刻意不抛出：知识库挂了不应该让整个应用起不来，只是 RAG 能力降级
            log.error("向量库初始化失败（重点排查：API Key 是否有效、网络是否可达、embedding 模型名是否正确）", e);
        }
        return vectorStore;
    }

    /**
     * 按「embedding 模型 + 文档内容 + 文件名」算摘要，决定用哪个缓存文件。
     * <p>
     * 排序是为了让结果与文档加载顺序无关，保证同一个知识库每次算出同一个 key。
     */
    private File resolveCacheFile(List<Document> documents) {
        StringBuilder fingerprint = new StringBuilder(embeddingModelName).append('\u0000');
        documents.stream()
                .map(d -> d.getMetadata().getOrDefault("filename", "") + "\u0000" + d.getText())
                .sorted(Comparator.naturalOrder())
                .forEach(s -> fingerprint.append(s).append('\u0001'));

        String digest = DigestUtil.sha256Hex(fingerprint.toString()).substring(0, 16);
        return Paths.get(cacheDir, "vector-store-" + digest + ".json").toFile();
    }

    private void saveCache(SimpleVectorStore vectorStore, File cacheFile) {
        try {
            Path parent = cacheFile.toPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            vectorStore.save(cacheFile);
            log.info("向量库已落盘缓存：{}", cacheFile.getAbsolutePath());
        } catch (Exception e) {
            // 缓存写失败只是下次启动慢一点，不影响功能
            log.warn("向量库缓存写入失败（不影响本次运行）", e);
        }
    }
}
