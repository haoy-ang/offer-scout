package com.haoy.offerscout.interview;

import java.util.List;

/**
 * 模拟面试复盘报告（结构化输出）。
 * <p>
 * 和前两个功能（简历诊断 / 岗位匹配）一样，用 {@code ChatClient.call().entity(InterviewReport.class)}
 * 让大模型按 JSON Schema 吐结果再反序列化回来。区别在于它的输入不是一份静态文档，
 * 而是**一整场多轮面试的对话记录** —— 所以这里多了一个 {@code verdict} 字段，
 * 直接给出「这场面试过没过」的结论，这才是模拟面试最有价值的产出。
 */
public record InterviewReport(
        /** 综合表现 0~100 */
        int overallScore,
        /**
         * 结论，枚举三态：{@link Verdict#PASS} / {@link Verdict#PENDING} / {@link Verdict#REJECT}。
         * <p>
         * 这里原本是 {@code String}，模型输出什么就是什么，前端只能靠
         * {@code includes("通过")} 猜配色 —— 猜错的后果是"没过"显示成"待定"。
         * 收敛成枚举后，配色判断从字符串嗅探变成 {@code switch}，契约也稳了。
         * 模型不守约时由 {@link Verdict#from(String)} 兜底，容错只在那一处。
         */
        Verdict verdict,
        /** 一句话总评：能不能过 + 最该补的一个短板 */
        String summary,
        /** 分维度表现 */
        List<DimensionScore> dimensions,
        /** 答得好的地方 */
        List<Point> highlights,
        /** 答砸或没答上的地方 */
        List<Point> issues,
        /** 逐条改进建议（按优先级排序） */
        List<Suggestion> suggestions
) {

    /** 单一维度的表现。name 是维度名，score 0~100，comment 是简短点评 */
    public record DimensionScore(
            String name,
            int score,
            String comment
    ) {
    }

    /**
     * 一条针对具体问答的点评。
     *
     * @param question 面试官的问题（或话题），尽量引用原话
     * @param comment  点评：好在哪 / 差在哪
     */
    public record Point(
            String question,
            String comment
    ) {
    }

    /**
     * 一条改进建议。
     *
     * @param priority 优先级，取值 high / medium / low
     * @param problem  问题定位
     * @param fix      具体改法
     */
    public record Suggestion(
            String priority,
            String problem,
            String fix
    ) {
    }
}
