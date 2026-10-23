package com.haoy.offerscout.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CitationAuditService} 的接线层测试：语料准备、开关、异常兜底。
 * <p>
 * 校验逻辑本身在 {@link CitationCheckerTest} 里覆盖；这里只测"接线"——
 * 因为接线错的地方往往不是算错，而是**每次都重算一遍语料**、或者**开关关不掉**。
 */
class CitationAuditServiceTest {

    private static final String CORPUS_TEXT = "压测指标示例：P99 从 320ms 降到 85ms，缓存命中率 92%。";

    private JobDocumentLoader loaderReturning(String text) {
        JobDocumentLoader loader = mock(JobDocumentLoader.class);
        when(loader.loadMarkdowns()).thenReturn(List.of(Document.builder().text(text).build()));
        return loader;
    }

    @Test
    @DisplayName("语料只构建一次：45 次校验只加载 1 次素材")
    void corpusIsBuiltOnlyOnce() {
        JobDocumentLoader loader = loaderReturning(CORPUS_TEXT);
        CitationAuditService service = new CitationAuditService(loader, true);

        for (int i = 0; i < 45; i++) {
            service.audit("随便什么问题", "命中率 92%");
        }

        // 素材 1.2MB，每次校验都重新解析的话，一次对话就能吃掉几百毫秒
        verify(loader, times(1)).loadMarkdowns();
    }

    @Test
    @DisplayName("语料里有该值 → 不追加提示")
    void noCaveatWhenValueIsGrounded() {
        CitationAuditService service = new CitationAuditService(loaderReturning(CORPUS_TEXT), true);

        assertThat(service.caveatFor("怎么写量化指标", "可以写「P99 从 320ms 降到 85ms」")).isNull();
    }

    @Test
    @DisplayName("语料里没有该值 → 追加提示，且提示里带上具体值")
    void caveatListsUngroundedValues() {
        CitationAuditService service = new CitationAuditService(loaderReturning(CORPUS_TEXT), true);

        String caveat = service.caveatFor("怎么写量化指标", "可以写「命中率从 61% 提升到 99.7%」");

        assertThat(caveat).isNotNull();
        assertThat(caveat).contains("61%", "99.7%", "查不到出处");
    }

    @Test
    @DisplayName("开关关掉后不追加提示，但 audit() 照样能算出结果")
    void disabledFlagOnlyStopsTheCaveatNotTheAudit() {
        CitationAuditService service = new CitationAuditService(loaderReturning(CORPUS_TEXT), false);

        assertThat(service.caveatFor("怎么写量化指标", "命中率 99.7%")).isNull();
        // 评测依赖这个语义：开关只控制"要不要写进回答"，不该控制"能不能测"
        assertThat(service.audit("怎么写量化指标", "命中率 99.7%").blocking()).hasSize(1);
    }

    @Test
    @DisplayName("素材加载失败不抛异常，按「无语料」降级 —— 校验挂了不能让用户拿不到回答")
    void loaderFailureDegradesInsteadOfThrowing() {
        JobDocumentLoader broken = mock(JobDocumentLoader.class);
        when(broken.loadMarkdowns()).thenThrow(new IllegalStateException("磁盘抽风"));
        CitationAuditService service = new CitationAuditService(broken, true);

        assertThat(service.caveatFor("问题", "命中率 99.7%")).isNull();
        assertThat(service.audit("问题", "命中率 99.7%").blocking()).isEmpty();
    }
}
