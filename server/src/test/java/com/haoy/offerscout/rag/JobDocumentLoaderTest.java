package com.haoy.offerscout.rag;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

/**
 * 知识库加载器测试。
 * <p>
 * 纯单元测试：不启动 Spring、不调用大模型，只验证「Markdown 能不能被正确切成片段」。
 */
class JobDocumentLoaderTest {

    @Test
    void testLoadMarkdowns() {
        JobDocumentLoader loader = new JobDocumentLoader(new PathMatchingResourcePatternResolver());

        List<Document> documents = loader.loadMarkdowns();

        Assertions.assertFalse(documents.isEmpty(), "知识库不应该为空");
        System.out.println("=== 知识库共加载 " + documents.size() + " 个片段 ===");

        documents.stream().limit(5).forEach(d -> {
            String text = d.getText().replaceAll("\\s+", " ").trim();
            System.out.println("- [" + d.getMetadata().get("filename") + "] "
                    + text.substring(0, Math.min(60, text.length())));
        });

        // 每个片段都要带上来源文件名，后面回答时才能标注出处
        Assertions.assertTrue(
                documents.stream().allMatch(d -> d.getMetadata().containsKey("filename")),
                "所有片段都应该带有 filename 元数据");
    }
}
