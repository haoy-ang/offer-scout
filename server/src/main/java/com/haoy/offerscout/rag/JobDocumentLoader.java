package com.haoy.offerscout.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文档加载器。
 * <p>
 * 从 classpath:document/ 目录读取所有 Markdown 文档。
 * RAG 的第一步就是「把资料变成可检索的文档」——这一步做不好，后面检索全是垃圾。
 */
@Component
@Slf4j
public class JobDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    public JobDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 加载 document 目录下全部 Markdown 文档。
     * <p>
     * 配置里 withHorizontalRuleCreateDocument(true) 表示：文档中的 `---` 分隔线会被切成独立的 Document。
     * 这样每一条知识点是一个独立的检索单元，粒度比整篇文档细得多，检索更准。
     */
    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();

                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", filename)
                        .build();

                List<Document> documents = new MarkdownDocumentReader(resource, config).get();
                allDocuments.addAll(documents);
                log.info("知识库文档已加载: {} -> {} 个片段", filename, documents.size());
            }
        } catch (IOException e) {
            log.error("知识库文档加载失败", e);
        }
        log.info("知识库文档加载完成，共 {} 个片段", allDocuments.size());
        return allDocuments;
    }
}
