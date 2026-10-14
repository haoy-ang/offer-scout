package com.haoy.offerscout.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 终止工具。
 * <p>
 * 给 Agent 一个「主动喊停」的手段。没有它，Agent 只能靠 maxSteps 兜底，
 * 要么白跑好几步浪费 token，要么还没说完就被强行掐断。
 * <p>
 * 注意：方法名 {@code doTerminate} 是被 {@code ToolCallAgent#act()} 硬编码识别的，
 * 改名会导致终止逻辑失效。
 */
public class TerminateTool {

    @Tool(description = "任务已经完成、不需要再调用任何工具时调用此工具来结束任务。传入一句简短的任务完成说明。")
    public String doTerminate(@ToolParam(description = "任务完成的一句话说明") String summary) {
        return "任务完成：" + summary;
    }
}
