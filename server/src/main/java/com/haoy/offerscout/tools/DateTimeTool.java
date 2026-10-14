package com.haoy.offerscout.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 时间工具。
 * <p>
 * 大模型的「知识」停在训练数据截止那天，它对今天几号一无所知。
 * 凡是涉及「今年秋招什么时候」「离毕业还有几个月」这类相对时间，必须让它自己查系统时间，
 * 而不是靠提示词里写死一句日期（写死只能覆盖一次对话上下文，写死在 System Prompt 里也会随日期过期）。
 */
public class DateTimeTool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA);

    @Tool(description = "获取当前系统的真实日期和时间。当问题涉及现在、今天、今年、还剩几个月这类相对时间时必须调用，不要凭记忆推断。")
    public String getCurrentDateTime(
            @ToolParam(description = "时区 ID，例如 Asia/Shanghai。不确定就传 Asia/Shanghai", required = false)
            String zoneId) {
        ZoneId zone;
        try {
            zone = (zoneId == null || zoneId.isBlank())
                    ? ZoneId.of("Asia/Shanghai")
                    : ZoneId.of(zoneId);
        } catch (Exception e) {
            zone = ZoneId.of("Asia/Shanghai");
        }
        LocalDateTime now = LocalDateTime.now(zone);
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        return String.format("当前时间：%s（%s），时区 %s，时间戳 %d",
                now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)),
                weekday,
                zone.getId(),
                now.atZone(zone).toEpochSecond());
    }
}
