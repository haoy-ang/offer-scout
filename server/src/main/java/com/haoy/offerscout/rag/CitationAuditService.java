package com.haoy.offerscout.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 断言校验的接线层：负责把「知识库全量文本」准备好，判定逻辑全在 {@link CitationChecker}。
 * <p>
 * <b>为什么校验对着全量语料、而不是当次召回的 top-4 片段？</b>
 * 第一版就是拿片段比的，45 条实测误伤 68%（详见 {@link CitationChecker} 类注释）——
 * 一个值在库里别处存在、只是这次没被召回，它依然有依据，不该被判成编造。
 * <p>
 * 顺带省掉一次成本：原设计要为校验再跑一次向量检索（多一次 embedding 调用），
 * 现在只在启动后把素材读成一段文本，之后每次校验都是纯内存字符串比对，**零外部调用**。
 * <p>
 * 代价：语料里恰好出现同一个数值时，真编造会被放过。1.2MB 素材里"99.6%"这种值偶然撞上的概率不高，
 * 但确实存在 —— 这是"少误伤"换"少漏报"的取舍，方向是刻意的：
 * <b>一个总是喊狼来了的提示，用户会直接无视，那还不如不提示。</b>
 */
@Component
@Slf4j
public class CitationAuditService {

    private final JobDocumentLoader jobDocumentLoader;

    /**
     * 是否把提示追加到回答末尾。
     * <p>
     * 默认开 —— 这一层存在的意义就是让用户看见"这个数我没依据"。
     * 跑答案质量评测时要关掉，否则 judge 看到的是"已自带免责声明"的回答，分数会被提示本身抬高。
     */
    private final boolean enabled;

    /** 懒加载的全量语料。volatile + 方法级同步：只构建一次，之后全是无锁读 */
    private volatile CitationChecker.Corpus corpus;

    public CitationAuditService(JobDocumentLoader jobDocumentLoader,
                                @Value("${offer-scout.rag.citation-audit-enabled:true}") boolean enabled) {
        this.jobDocumentLoader = jobDocumentLoader;
        this.enabled = enabled;
    }

    /**
     * 纯校验，不看开关、不抛异常。
     * <p>
     * 评估要用这个：开关只控制"要不要把结果写进回答"，不该控制"能不能算出结果"。
     */
    public CitationChecker.Result audit(String question, String answer) {
        if (answer == null || answer.isBlank()) {
            return CitationChecker.Result.empty();
        }
        try {
            return CitationChecker.check(answer, corpus());
        } catch (Exception e) {
            log.warn("断言校验失败，按「无语料」处理：{}", e.getMessage());
            return CitationChecker.Result.empty();
        }
    }

    /**
     * 校验并把提示文本交给调用方去追加；不需要提示时返回 {@code null}。
     * <p>
     * 任何异常都吞掉：这是**附加能力**，它挂了也不能让用户拿不到回答。
     */
    public String caveatFor(String question, String answer) {
        if (!enabled) {
            return null;
        }
        try {
            CitationChecker.Result result = audit(question, answer);
            String caveat = CitationChecker.buildCaveat(result);
            if (caveat != null) {
                log.info("断言校验拦截 {} 处无出处断言：{}",
                        result.blocking().size(),
                        result.blocking().stream().map(CitationChecker.Citation::describe).toList());
            }
            return caveat;
        } catch (Exception e) {
            log.warn("断言校验异常，跳过提示：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 知识库全量文本，进程内只构建一次。
     * <p>
     * 放在这里而不是启动时构建：只有真的有人问了带知识库的问题才需要它，
     * 没必要让每次启动都多花时间解析一遍素材。
     * <p>
     * ⚠️ 素材是打包在 classpath 里的静态文件，运行期不会变，所以缓存到进程结束是安全的。
     * 如果将来改成"运行时可上传素材"，这里必须跟着失效，否则新素材永远校验不过。
     */
    private CitationChecker.Corpus corpus() {
        CitationChecker.Corpus local = corpus;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (corpus == null) {
                long start = System.currentTimeMillis();
                List<Document> documents = jobDocumentLoader.loadMarkdowns();
                StringBuilder sb = new StringBuilder();
                for (Document doc : documents) {
                    if (doc.getText() != null) {
                        sb.append(doc.getText()).append('\n');
                    }
                }
                corpus = CitationChecker.Corpus.of(sb.toString());
                log.info("断言校验语料就绪：{} 个片段 / {} 字符，耗时 {} ms",
                        documents.size(), sb.length(), System.currentTimeMillis() - start);
            }
            return corpus;
        }
    }
}
