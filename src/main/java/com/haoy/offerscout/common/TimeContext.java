package com.haoy.offerscout.common;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 时间上下文：供大模型提示词拼接的「今天是几号」。
 * <p>
 * <b>为什么要抽成一个类、而不是各处各拼一遍？</b>
 * 因为现在是「哪个 Service 拼提示词，就得记得拼日期」。而大模型的知识停在训练数据截止那天 ——
 * 哪一处提示词漏了这个日期，模型就会在哪一页露馅：把「今年」「现在」「还剩几个月」
 * 按训练期的年份去理解。本项目实测过一次：简历诊断页因为系统提示词里没有任何时间信息，
 * 模型直接按 2024 年给判断，用户看到后一脸问号。
 * <p>
 * 所以口径收到一处：新增一处提示词时调 {@link #promptSection()} 即可，别自己写日期字符串。
 * <p>
 * <b>为什么不用 Advisor / defaultSystem 统一注入？</b>
 * 本项目有多个各自 {@code ChatClient.builder(...)} 的 Service（简历诊断、岗位匹配、面试、主对话），
 * 它们刻意互不共用（无状态的诊断不该沾染会话记忆）。要在那一层统一，得再包一层自定义 Advisor，
 * 复杂度不划算。提示词拼接处调一次静态方法，最直白也最不容易漏。
 */
public final class TimeContext {

    private TimeContext() {
    }

    /**
     * 拼进系统提示词的「时间」段落。
     * <p>
     * 返回带前导换行的片段，调用方直接接在提示词末尾即可（放末尾是有意的：离模型开始生成最近，
     * 约束力更强）。
     * <p>
     * 注意：本项目的提示词一律用字符串拼接 / {@code String.replace}，不用 {@code formatted} ——
     * 提示词里出现字面 {@code %}（如"提升 X%"）时 {@code formatted} 会直接抛异常。
     */
    public static String promptSection() {
        LocalDate today = LocalDate.now();
        String weekday = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        return "\n## 时间\n"
                + "今天的日期是 " + today + "（" + weekday + "）。"
                + "凡是「今年 / 现在 / 最近 / 还剩几个月 / 几几届」这类相对时间，"
                + "一律以这个日期为准，不要拿训练数据里的年份去推断（那会答成过时的年份），"
                + "也不要臆造当前日期。\n";
    }
}
