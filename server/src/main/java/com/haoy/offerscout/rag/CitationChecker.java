package com.haoy.offerscout.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 断言校验器：把回答里「具体到能被面试官追问」的事实抽出来，逐条查它在知识库里有没有出处。
 * <p>
 * <b>为什么需要它？</b>系统提示词里已经写了「版本号/配置项/百分比没依据就别给」，
 * 但那是<b>请求</b>不是<b>保证</b> —— 45 条答案质量评测里仍有 6~8 条编造了具体数值
 * （"命中率从 82%→99.2%"、"BF.RESERVE ERROR_RATE 0.001"、"P99 响应 < 95ms"）。
 * 提示词是软约束，模型可以不听；这一层是<b>确定性的硬兜底</b>。
 * <p>
 * <b>它做什么、不做什么</b>：它<b>不</b>判断"这句话对不对"，只判断"这个具体值在知识库里有没有出现过"。
 * 这是纯字符串层面的判定，所以：
 * <ul>
 *   <li>它<b>不会</b>改写回答 —— 正则分不清「编的」和「对的但资料里没写」（比如 HashMap 默认容量 16），
 *       自动改写会误伤正确内容。它只负责把"无出处"标出来，把判断权还给用户。</li>
 *   <li>它<b>不回写业务状态、不调模型、不查向量库</b> —— 纯函数，所以能被单测钉死每一条边界。</li>
 * </ul>
 * <h2>⚠️ 判据是「全量知识库」，不是「这次召回的 4 段」—— 这条是踩出来的</h2>
 * 第一版对着「当次 top-4 检索片段」判，45 条实测：拦住了 7/8 的编造，但**误伤 25/37 = 68%**。
 * 逐条看误伤内容：{@code 100%}、{@code 30秒}（2MSL 就是 30 秒）、{@code spring.datasource.url}
 * （真实存在的配置键）、"例如 P99 从 320ms 降到 85ms"（举例模板）。
 * <b>全是"对的、但这次没被召回"，不是"编的"。</b>
 * <p>
 * 一个值在知识库别处存在、只是这次没进 top-4，它依然**有依据** ——
 * 用「本次召回」当判据，测出来的其实是"召回覆盖度"，不是"编造率"。
 * 所以现在改成对着**全部素材**查：
 * <ul>
 *   <li>语义更对：有依据 = 知识库里有，与检索打分无关。</li>
 *   <li>成本更低：不用为校验再调一次 embedding（原来每次 RAG 请求多一次向量检索）。</li>
 *   <li>代价要说清：语料里恰好出现同一个数值时，真编造会被放过（假阴性）。
 *       1.2MB 素材里"99.6%"这种四位数偶然撞上的概率不高，但确实存在。</li>
 * </ul>
 * <h2>严重度为什么分两档（实测数据定的，不是拍的）</h2>
 * <ul>
 *   <li>{@link Severity#HIGH}：百分比、性能指标、「配置项 + 具体值」。共同点 —— 它们几乎<b>只可能</b>
 *       来自"某次真实测量"或"某份真实文档"，编不出来也记不准，所以"库里查不到"≈"基本是编的"。
 *       裸配置键名（不带值）只算 MEDIUM：写对键名是有可能的，写对"键 + 值"不可能。</li>
 *   <li>{@link Severity#MEDIUM}：版本号、代码调用。版本号里混着大量通用常识（"Spring Boot 3.4"）；
 *       代码调用查到了函数名也不代表参数是对的。这两类误报率高，只记日志、不进提示。
 *       例外是<b>三段式</b>版本号（{@code 3.4.1}）—— 没人凭记忆写三段，升为 HIGH。</li>
 * </ul>
 */
public final class CitationChecker {

    /** 单条答案最多抽多少个断言，防止超长回答把正则跑飞 */
    private static final int MAX_CITATIONS = 40;

    /** 提示里最多列几个，多了只报数量 */
    private static final int MAX_SHOWN_IN_CAVEAT = 5;

    /**
     * 判断「配置项后面有没有跟具体值」时往后看多少字符。
     * <p>
     * 16 个字符大约能覆盖 "：3000"、"调成 3000"、"= 5000"、" 0.001" 这些写法，
     * 又不会跨到下一句去误捡一个无关的数字。
     */
    private static final int CONFIG_VALUE_WINDOW = 16;

    /**
     * 允许「产品名 + 裸数字」被当成版本号的产品。
     * 其它产品必须是 {@code x.y} 形式才算 —— 否则 "Kafka 3 个副本" 会被误判成 "Kafka 3"。
     */
    private static final Set<String> BARE_VERSION_OK = Set.of(
            "jdk", "jre", "java", "python", "node", "node.js", "vue", "k8s", "kubernetes", "cuda");

    // ────────────────────────── 抽取用的正则 ──────────────────────────

    /** 百分比：「72%」「0.0002%」「5个百分点」 */
    private static final Pattern PERCENT = Pattern.compile(
            "(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)\\s*(?:%|％|个百分点)");

    /**
     * 量化指标：「8.2min」「95ms」「1200 QPS」「200 并发」「3 倍」。
     * <p>
     * 刻意<b>不</b>收「3 个要点」「第 2 步」这类纯计数 —— 它们不是事实断言，抓了全是噪音。
     * 单位按长度从长到短排列（{@code ms} 必须在 {@code s} 前面，否则 "8ms" 会被切成 "8m"+"s"）。
     */
    private static final Pattern METRIC = Pattern.compile(
            "(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)\\s*"
                    + "(ms|毫秒|μs|us|ns|min|分钟|小时|秒|QPS|TPS|RPS|MB|KB|GB|TB|倍|万|亿|并发"
                    + "|[sShH](?![A-Za-z0-9]))");

    /** 具名产品 + 版本：「Nacos 2.2」「Spring Boot 3.4.1」「JDK 21」 */
    private static final Pattern VERSION_NAMED = Pattern.compile(
            "(?<![A-Za-z])(JDK|JRE|Java|Spring\\s*Boot|Spring\\s*Cloud|MySQL|MariaDB|Redis|Nacos|Kafka"
                    + "|RocketMQ|RabbitMQ|Tomcat|Jetty|Maven|Gradle|Vue|React|Node(?:\\.js)?|Python"
                    + "|Kubernetes|K8s|Docker|Elasticsearch|MongoDB|Seata|Sentinel|Dubbo|ZooKeeper|Netty"
                    + "|MyBatis|PostgreSQL|Hadoop|Spark|Flink|ClickHouse|ShardingSphere|OpenFeign"
                    + "|Eureka|Apollo|SkyWalking|Prometheus|Grafana|Arthas|Qwen|GPT|Llama|CUDA)"
                    + "\\s*v?(\\d+(?:\\.\\d+)*[A-Za-z0-9._-]*)");

    /** 通用三段版本号：「3.4.1」—— 没带产品名也要抓，因为三段数字在中文技术文里几乎只可能是版本号 */
    private static final Pattern VERSION_GENERIC = Pattern.compile(
            "(?<![A-Za-z0-9.])(\\d+\\.\\d+\\.\\d+)(?![0-9])");

    /** 点分配置项：「feign.client.config.default.connectTimeout」 */
    private static final Pattern CONFIG_DOTTED = Pattern.compile(
            "(?<![A-Za-z0-9_.-])([a-z][A-Za-z0-9_-]*(?:\\.[A-Za-z][A-Za-z0-9_-]*){2,})(?![A-Za-z0-9_-])");

    /** 全大写常量 / 配置键：「TREEIFY_THRESHOLD」「ERROR_RATE」「Q4_K_M」 */
    private static final Pattern CONFIG_CAPS = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Z0-9][A-Z0-9]*(?:_[A-Z0-9]+)+)(?![A-Za-z0-9_])");

    /** Redis 一类命令标识：「BF.ADD」「BF.RESERVE」 */
    private static final Pattern CONFIG_COMMAND = Pattern.compile(
            "(?<![A-Za-z0-9_.])([A-Z]{2,}\\.[A-Z]{2,}(?:\\.[A-Z]{2,})*)(?![A-Za-z0-9_])");

    /**
     * 代码调用：「getObject()」「nextLong(0,300)」「lock(60, TimeUnit.SECONDS)」。
     * <p>
     * 括号在中文里也很常见（"Redis(缓存)"），所以匹配完还要再过一道 {@link #looksLikeCode} 校验。
     */
    private static final Pattern CODE_CALL = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_.]*)\\s*\\(([^()\\n]{0,80})\\)");

    private CitationChecker() {
    }

    // ────────────────────────── 对外模型 ──────────────────────────

    public enum Kind {
        PERCENT("百分比"),
        METRIC("量化指标"),
        VERSION("版本号"),
        CONFIG_KEY("配置项/常量名"),
        CODE("代码调用");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 无出处时的处理档次，判据见类注释 */
    public enum Severity { HIGH, MEDIUM }

    /**
     * @param raw       回答里的原始写法（用于展示）
     * @param token     归一化后用于匹配的核心片段（数字部分 / 版本部分 / 键名）
     * @param supported 是否在知识库里找到了出处
     */
    public record Citation(String raw, String token, Kind kind, Severity severity, boolean supported) {
        public String describe() {
            return kind.label() + "「" + raw + "」";
        }
    }

    public record Result(List<Citation> citations, boolean corpusEmpty) {

        public static Result empty() {
            return new Result(List.of(), true);
        }

        /** 全部无出处的断言 */
        public List<Citation> unsupported() {
            return citations.stream().filter(c -> !c.supported()).toList();
        }

        /** 无出处且属于「基本只可能是编的」那一档 —— 只有这些会进提示 */
        public List<Citation> blocking() {
            return citations.stream()
                    .filter(c -> !c.supported() && c.severity() == Severity.HIGH)
                    .toList();
        }

        public boolean hasBlocking() {
            return !blocking().isEmpty();
        }
    }

    /**
     * 知识库全量文本。
     * <p>
     * 做成对象是因为要对同一份语料反复查：{@code flat} 那一版（抹掉 {@code -}/{@code _} 并小写）
     * 预先算好，避免每个配置项都重新 flatten 一遍 1.2MB 的字符串。
     * 这是唯一一处"为了性能牺牲纯函数感"的地方，但它是不可变对象，行为仍然确定。
     */
    public static final class Corpus {

        private final String text;

        private final String flat;

        private Corpus(String text) {
            this.text = text;
            this.flat = text.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        }

        public static Corpus of(String raw) {
            return new Corpus(raw == null ? "" : normalizeHalfWidth(raw));
        }

        public static Corpus empty() {
            return new Corpus("");
        }

        public boolean isEmpty() {
            return text.isBlank();
        }

        public String text() {
            return text;
        }

        public String flat() {
            return flat;
        }
    }

    // ────────────────────────── 主流程 ──────────────────────────

    /**
     * @param answer 模型刚生成的回答
     * @param corpus 知识库全量文本（不是本次召回的片段，理由见类注释）
     */
    public static Result check(String answer, Corpus corpus) {
        if (answer == null || answer.isBlank()) {
            return Result.empty();
        }
        String text = normalizeHalfWidth(answer);
        Corpus ctx = corpus == null ? Corpus.empty() : corpus;

        List<Citation> raw = new ArrayList<>();
        collectPercent(text, raw);
        collectMetric(text, raw);
        collectVersion(text, raw);
        collectConfigKeys(text, raw);
        collectCodeCalls(text, raw);

        // 去重：同一句话里 "92%" 出现三次只提示一次
        Map<String, Citation> merged = new LinkedHashMap<>();
        for (Citation c : raw) {
            String key = c.kind() + "|" + c.token().toLowerCase(Locale.ROOT);
            merged.putIfAbsent(key, new Citation(c.raw(), c.token(), c.kind(), c.severity(),
                    isSupported(c, ctx)));
        }
        return new Result(List.copyOf(merged.values()), ctx.isEmpty());
    }

    /** 便利重载：直接给原始语料字符串 */
    public static Result check(String answer, String corpusRaw) {
        return check(answer, Corpus.of(corpusRaw));
    }

    // ────────────────────────── 各类型的抽取 ──────────────────────────

    private static void collectPercent(String text, List<Citation> out) {
        Matcher m = PERCENT.matcher(text);
        while (m.find() && out.size() < MAX_CITATIONS) {
            out.add(new Citation(compress(m.group()), m.group(1), Kind.PERCENT, Severity.HIGH, false));
        }
    }

    private static void collectMetric(String text, List<Citation> out) {
        Matcher m = METRIC.matcher(text);
        while (m.find() && out.size() < MAX_CITATIONS) {
            out.add(new Citation(compress(m.group()), m.group(1), Kind.METRIC, Severity.HIGH, false));
        }
    }

    private static void collectVersion(String text, List<Citation> out) {
        Matcher named = VERSION_NAMED.matcher(text);
        while (named.find() && out.size() < MAX_CITATIONS) {
            String product = named.group(1).replaceAll("\\s+", " ");
            String version = named.group(2);
            // "Kafka 3 个副本" 这种不能被当成版本号：没有小数点时只认少数几个产品
            if (!version.contains(".") && !BARE_VERSION_OK.contains(product.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(new Citation(compress(product + " " + version), version, Kind.VERSION,
                    versionSegmentCount(version) >= 3 ? Severity.HIGH : Severity.MEDIUM, false));
        }

        Matcher generic = VERSION_GENERIC.matcher(text);
        while (generic.find() && out.size() < MAX_CITATIONS) {
            String version = generic.group(1);
            // 三段式：没人凭记忆写得出，编出来的可能性远大于"通用常识"
            out.add(new Citation(version, version, Kind.VERSION, Severity.HIGH, false));
        }
    }

    private static void collectConfigKeys(String text, List<Citation> out) {
        Matcher dotted = CONFIG_DOTTED.matcher(text);
        while (dotted.find() && out.size() < MAX_CITATIONS) {
            out.add(new Citation(dotted.group(1), dotted.group(1), Kind.CONFIG_KEY,
                    configSeverity(text, dotted.end()), false));
        }
        Matcher caps = CONFIG_CAPS.matcher(text);
        while (caps.find() && out.size() < MAX_CITATIONS) {
            out.add(new Citation(caps.group(1), caps.group(1), Kind.CONFIG_KEY,
                    configSeverity(text, caps.end()), false));
        }
        Matcher command = CONFIG_COMMAND.matcher(text);
        while (command.find() && out.size() < MAX_CITATIONS) {
            out.add(new Citation(command.group(1), command.group(1), Kind.CONFIG_KEY,
                    configSeverity(text, command.end()), false));
        }
    }

    /**
     * 配置项的风险等级，取决于**它后面有没有跟一个具体值**。
     * <p>
     * 实测依据：第一版把所有配置项一刀切成 HIGH，结果误伤了
     * {@code spring.datasource.url}、{@code spring.cloud.nacos.discovery.server-addr}、
     * {@code DB_TRX_ID} —— 这些键**本身是对的**，只是素材里没写。
     * 光凭记忆写对一个键名是可能的（Spring 的键有固定形态），写对「键 + 值」不可能。
     * 所以要拦的是 "connectTimeout 调成 3000" 这种组合，而不是裸键名。
     */
    private static Severity configSeverity(String text, int keyEnd) {
        int window = Math.min(text.length(), keyEnd + CONFIG_VALUE_WINDOW);
        for (int i = keyEnd; i < window; i++) {
            if (Character.isDigit(text.charAt(i))) {
                return Severity.HIGH;
            }
        }
        return Severity.MEDIUM;
    }

    private static void collectCodeCalls(String text, List<Citation> out) {
        Matcher m = CODE_CALL.matcher(text);
        while (m.find() && out.size() < MAX_CITATIONS) {
            String name = m.group(1);
            String args = m.group(2).trim();
            if (!looksLikeCode(name, args)) {
                continue;
            }
            out.add(new Citation(compress(m.group()), name, Kind.CODE, Severity.MEDIUM, false));
        }
    }

    /**
     * 把「中文括号」和「方法调用」区分开。
     * <p>
     * 判据：空括号 / 参数里含数字 / 标识符本身带点（方法链）三者取其一。
     * 这样 {@code getObject()}、{@code nextLong(0,300)}、{@code ThreadLocalRandom.current()} 都算代码，
     * 而「Redis(缓存)」「SUCCESS(成功)」这种说明性括号不算。
     */
    private static boolean looksLikeCode(String name, String args) {
        if (name.length() < 3) {
            return false;
        }
        if (args.isEmpty() || name.contains(".")) {
            return true;
        }
        return args.chars().anyMatch(Character::isDigit);
    }

    private static int versionSegmentCount(String version) {
        int dots = 0;
        for (int i = 0; i < version.length(); i++) {
            if (version.charAt(i) == '.') {
                dots++;
            }
        }
        return dots + 1;
    }

    // ────────────────────────── 出处校验 ──────────────────────────

    private static boolean isSupported(Citation c, Corpus corpus) {
        if (corpus.isEmpty()) {
            return false;
        }
        String ctx = corpus.text();
        return switch (c.kind()) {
            // 数字类只比数字本身：资料说 "95ms"、回答写 "95 ms"，也要算有出处
            case PERCENT, METRIC -> containsNumber(ctx, c.token());
            case VERSION -> containsVersion(ctx, c.token());
            case CONFIG_KEY -> containsKey(corpus, c.token());
            case CODE -> containsIdentifier(ctx, c.token());
        };
    }

    /**
     * 数字比对必须带边界，否则 "99.6" 会在 "199.6" 里命中（这是假阴性方向：把编造的判成有出处）。
     * 后缀只排数字、不排点，这样回答写 "3.4" 而资料写 "3.4.1" 仍然算有出处。
     */
    private static boolean containsNumber(String ctx, String token) {
        int from = 0;
        while (true) {
            int i = ctx.indexOf(token, from);
            if (i < 0) {
                return false;
            }
            char before = i == 0 ? ' ' : ctx.charAt(i - 1);
            int end = i + token.length();
            char after = end >= ctx.length() ? ' ' : ctx.charAt(end);
            if (!Character.isDigit(before) && before != '.' && !Character.isDigit(after)) {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean containsVersion(String ctx, String token) {
        return Pattern.compile("(?<![A-Za-z0-9.])" + Pattern.quote(token) + "(?![0-9])")
                .matcher(ctx).find();
    }

    /**
     * 键名容错：先按原样找；找不到再抹掉 {@code -} / {@code _} 并统一小写再找 ——
     * 因为同一个配置项在不同资料里可能写成 {@code connect-timeout} / {@code connectTimeout} / {@code connect_timeout}。
     */
    private static boolean containsKey(Corpus corpus, String token) {
        if (containsIdentifier(corpus.text(), token)) {
            return true;
        }
        String flatToken = token.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        return corpus.flat().contains(flatToken);
    }

    private static boolean containsIdentifier(String ctx, String token) {
        return Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(token) + "(?![A-Za-z0-9_])")
                .matcher(ctx).find();
    }

    // ────────────────────────── 给用户的提示 ──────────────────────────

    /**
     * 拼一段给用户看的提示；没有需要拦截的断言时返回 {@code null}。
     * <p>
     * 刻意写成"提醒用户自己填"，而不是"我把这段删了"：
     * 用户的目的本来就是把这类数字填进简历，删掉等于把有用信息也删了；
     * 提醒他"这是编的，换成你实测的"才是真正解决了他要解决的问题。
     */
    public static String buildCaveat(Result result) {
        List<Citation> blocking = result.blocking();
        if (blocking.isEmpty()) {
            return null;
        }
        String listed = blocking.stream()
                .limit(MAX_SHOWN_IN_CAVEAT)
                .map(c -> "`" + c.raw() + "`")
                .reduce((a, b) -> a + "、" + b)
                .orElse("");

        StringBuilder sb = new StringBuilder("\n\n---\n> ⚠️ ");
        if (result.corpusEmpty()) {
            sb.append("知识库是空的，");
        }
        sb.append("上面有 ").append(blocking.size()).append(" 处具体数值/配置在知识库里查不到出处：").append(listed);
        if (blocking.size() > MAX_SHOWN_IN_CAVEAT) {
            sb.append(" 等");
        }
        sb.append("。\n> 这类数字面试官一定会追问「怎么测出来的」，请换成你项目的实测值，别直接照抄。");
        return sb.toString();
    }

    // ────────────────────────── 小工具 ──────────────────────────

    /** 全角数字/百分号/句点转半角 —— 用户提问和模型回答里两种写法都常见 */
    private static String normalizeHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else if (c == '％') {
                sb.append('%');
            } else if (c == '．') {
                sb.append('.');
            } else if (c == '　') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String compress(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
