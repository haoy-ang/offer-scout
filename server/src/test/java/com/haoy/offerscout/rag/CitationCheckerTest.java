package com.haoy.offerscout.rag;

import com.haoy.offerscout.rag.CitationChecker.Citation;
import com.haoy.offerscout.rag.CitationChecker.Kind;
import com.haoy.offerscout.rag.CitationChecker.Result;
import com.haoy.offerscout.rag.CitationChecker.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CitationChecker} 的单测。
 * <p>
 * <b>用例来源</b>：正向用例里那些"编造"的字符串<b>不是</b>我现编的，
 * 而是从 {@code target/eval-reports/answer-quality.md} 的 judge 判词里逐条抄下来的真实产物
 * （"命中率从 82%→99.2%"、"BF.RESERVE ERROR_RATE 0.001"、"P99 响应 < 95ms"、
 * "构建耗时从 8.2min 降至 3.5min"、"单元测试覆盖率 91%"）。
 * 用真实数据当测试夹具，才能保证正则调的是"实际会遇到的形态"，而不是"我想象的形态"。
 * <p>
 * 这是纯 JVM 测试：不连 MySQL/Redis、不调模型，毫秒级跑完，可以进 CI 主档。
 */
class CitationCheckerTest {

    // ────────────────── 该抓到的：编造 ──────────────────

    @Test
    @DisplayName("语料为空 + 满篇具体数值 → 全部判无出处，且都是 HIGH")
    void fabricatedMetricsWithEmptyContextAreBlocked() {
        // 拼自真实评估样本（#16 / #32 / #42）的编造细节
        String answer = """
                RedisBloom 用 BF.RESERVE 和 ERROR_RATE 0.001 初始化，
                缓存命中率从 82% 提升到 99.2%，CI 构建耗时从 8.2min 降至 3.5min，
                P99 响应 < 95ms，单元测试覆盖率 91%。
                """;

        Result result = CitationChecker.check(answer, "");

        assertThat(result.corpusEmpty()).isTrue();
        assertThat(result.blocking()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(result.blocking()).extracting(Citation::raw)
                .contains("82%", "99.2%", "8.2min", "3.5min", "95ms", "91%");
        assertThat(result.blocking()).allMatch(c -> c.severity() == Severity.HIGH);
        assertThat(CitationChecker.buildCaveat(result)).contains("查不到出处");
    }

    @Test
    @DisplayName("配置项全名写成这样也算 HIGH —— 记错的配置项直接坑人")
    void fabricatedConfigKeyIsBlocked() {
        Result result = CitationChecker.check(
                "把 feign.client.config.default.connectTimeout 调成 3000 就行", "");

        assertThat(result.blocking()).extracting(Citation::raw)
                .contains("feign.client.config.default.connectTimeout");
    }

    @Test
    @DisplayName("裸配置键名只算 MEDIUM —— 键名可能记对，值不可能，拦的应该是「键 + 值」")
    void bareConfigKeyWithoutValueIsNotBlocking() {
        // 实测误伤来源：这些键本身是对的，只是素材里没写，提示用户"查不到出处"纯属噪音
        Result result = CitationChecker.check(
                "看一下 spring.datasource.url、spring.cloud.nacos.discovery.server-addr 和 DB_TRX_ID", "");

        assertThat(result.citations()).extracting(Citation::raw)
                .contains("spring.datasource.url", "spring.cloud.nacos.discovery.server-addr", "DB_TRX_ID");
        assertThat(result.blocking()).as("没有跟具体值，不进提示").isEmpty();
    }

    @Test
    @DisplayName("配置项后面跟了具体值 → 升回 HIGH")
    void configKeyFollowedByValueIsBlocking() {
        Result result = CitationChecker.check("BF.RESERVE 的 ERROR_RATE 设成 0.001 就行", "");

        assertThat(result.blocking()).extracting(Citation::raw).contains("ERROR_RATE");
    }

    @Test
    @DisplayName("三段式版本号判 HIGH：没人凭记忆写得出 3.4.1")
    void threeSegmentVersionIsBlocked() {
        Result result = CitationChecker.check("建议用 Nacos 2.2 和 Spring Boot 3.4.1", "");

        // 2.2 只有两段 → 有可能是通用常识，降为 MEDIUM，不进提示
        assertThat(result.unsupported()).hasSize(2);
        assertThat(result.blocking()).extracting(Citation::raw).containsExactly("Spring Boot 3.4.1");
    }

    // ────────────────── 不该抓的：引用 / 常识 / 计数 ──────────────────

    @Test
    @DisplayName("语料里有的数值不算编造 —— 忠实转述不能被误伤")
    void numbersPresentInContextAreSupported() {
        String corpus = "B+ 树树高通常只有 3～4 层，比二叉树矮胖得多。压测 200 并发，P99 95ms，缓存命中率 99.2%。";
        String answer = "压测 200 并发时 P99 是 95ms，缓存命中率达到 99.2%，树高只有 3～4 层。";

        Result result = CitationChecker.check(answer, corpus);

        assertThat(result.citations()).isNotEmpty();
        assertThat(result.unsupported()).as("全部都能在语料里找到出处").isEmpty();
        assertThat(CitationChecker.buildCaveat(result)).isNull();
    }

    @Test
    @DisplayName("判据是全量语料而非本次召回：没被检索到、但库里别处有，也算有依据")
    void valueElsewhereInCorpusCountsAsSupported() {
        // 这是 68% 误伤的根因：一个真实存在的值，只是这次没进 top-4 检索片段
        String corpus = """
                简历篇幅：一页纸最好。
                ---
                压测指标示例：P99 从 320ms 降到 85ms，命中率 92%。
                """;
        String answer = "可以把「P99 从 320ms 降到 85ms」写成量化结果，命中率写 92%。";

        Result result = CitationChecker.check(answer, corpus);

        assertThat(result.unsupported()).isEmpty();
    }

    @Test
    @DisplayName("纯计数和常识性数字不抓 —— 「3 个要点」「阈值 8」不是事实断言")
    void countsAndCommonSenseAreIgnored() {
        String answer = "先把这 3 个要点记住，第 2 步再看 4 类场景。HashMap 在链表长度超过 8 时树化，小于 6 时退化。";

        Result result = CitationChecker.check(answer, "");

        assertThat(result.citations()).isEmpty();
        assertThat(CitationChecker.buildCaveat(result)).isNull();
    }

    @Test
    @DisplayName("配置项分隔符写法不同也能匹配上（- / _ / 驼峰）")
    void configKeySeparatorVariantsMatch() {
        Result result = CitationChecker.check(
                "调 feign.client.config.default.connectTimeout 就行",
                "feign.client.config.default.connect-timeout=3000");

        assertThat(result.unsupported()).isEmpty();
    }

    @Test
    @DisplayName("代码调用：片段里有 getObject 就算有出处，没有的只记 MEDIUM 不打扰用户")
    void codeCallsAreCheckedButNotBlocking() {
        Result result = CitationChecker.check(
                "可以用 getObject() 拿到早期引用；ThreadLocalRandom.current().nextLong(0,300) 生成随机值",
                "三级缓存里放的是 ObjectFactory 工厂对象，getObject() 时按需生成代理或原始对象。");

        assertThat(result.unsupported()).extracting(Citation::token).contains("nextLong");
        assertThat(result.blocking()).as("代码调用只会是 MEDIUM").isEmpty();
    }

    // ────────────────── 边界 ──────────────────

    @Test
    @DisplayName("数字比对必须带边界：99.6% 不能因为片段里有 199.6% 就被判成有出处")
    void numberBoundaryPreventsFalseNegative() {
        Result result = CitationChecker.check("命中率 99.6%", "旧版本命中率 199.6%");

        assertThat(result.unsupported()).extracting(Citation::token).containsExactly("99.6");
    }

    @Test
    @DisplayName("全角数字/百分号也能识别")
    void fullWidthDigitsAreNormalized() {
        Result result = CitationChecker.check("命中率 ９９.６％", "");

        assertThat(result.blocking()).extracting(Citation::token).containsExactly("99.6");
    }

    @Test
    @DisplayName("空输入不炸")
    void handlesEmptyInput() {
        assertThat(CitationChecker.check(null, (String) null).citations()).isEmpty();
        assertThat(CitationChecker.check("", "").citations()).isEmpty();
        assertThat(CitationChecker.check("   ", "").corpusEmpty()).isTrue();
        assertThat(CitationChecker.buildCaveat(Result.empty())).isNull();
    }

    @Test
    @DisplayName("语料为空时，任何具体数值都判无出处 —— 这是编造最猖獗的场景")
    void emptyCorpusMakesEverySpecificValueUnsupported() {
        Result result = CitationChecker.check("命中率 99.6%", "");

        assertThat(result.corpusEmpty()).isTrue();
        assertThat(result.blocking()).hasSize(1);
        // 提示里要说清是"知识库空的"，而不是"你写错了"
        assertThat(CitationChecker.buildCaveat(result)).contains("知识库是空的");
    }

    @Test
    @DisplayName("重复出现的同一个值只提示一次")
    void duplicateValuesAreMerged() {
        Result result = CitationChecker.check("提升 92%，再看这 92%，最后确认 92%。", "");

        assertThat(result.blocking()).hasSize(1);
        assertThat(result.blocking().get(0).kind()).isEqualTo(Kind.PERCENT);
    }
}
