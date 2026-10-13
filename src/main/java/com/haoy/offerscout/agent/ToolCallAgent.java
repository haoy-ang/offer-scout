package com.haoy.offerscout.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.haoy.offerscout.agent.model.AgentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会调用工具的代理：把 ReAct 的 think / act 落地成「模型选工具 → 手动执行」。
 * <p>
 * <b>为什么不使用 Spring AI 内置的自动工具执行？</b>
 * 自动执行对上层是黑盒：Agent 拿不到「模型选中了哪些工具、参数是什么、执行结果如何」，
 * 也就没法自己控制循环、做步骤展示、或在合适的时机终止。
 * 所以这里显式关掉内置执行，自己维护消息上下文。
 * <p>
 * <b>关于收尾轮（final turn）</b>：模型调用 doTerminate 时，它那一轮的文本往往只是一句
 * 「信息已齐，可以作答了」的前言 —— 真正的建议还没写。如果这时直接结束循环，
 * 调用方会拿到一句空话。所以这里不收工，而是追加一条指令、关掉工具、再问模型一次，
 * 强制它把完整结论写出来。
 */
@Slf4j
public class ToolCallAgent extends ReActAgent {

    /** 收尾轮指令：告诉模型工具已关闭，安心输出结论 */
    private static final String FINAL_TURN_PROMPT =
            "资料已经收集完毕，现在不需要再调用任何工具。请输出给用户的最终完整建议，"
                    + "使用 Markdown 分节，先给结论再给可执行动作，并标注引用到的资料来源文件名。";

    /** 可用工具集合 */
    private final ToolCallback[] availableTools;

    /** 工具执行器：按模型给出的 tool_call 真正把方法跑起来 */
    private final ToolCallingManager toolCallingManager;

    /**
     * 带工具的对话选项。两个关键设置：
     * <ul>
     *   <li>{@code internalToolExecutionEnabled = false} —— 关掉框架的自动工具执行</li>
     *   <li>{@code toolCallbacks} —— 把工具挂到 options 上</li>
     * </ul>
     * 工具必须挂在 options 里，而不是只在 ChatClient 的链式调用上声明：
     * 因为 {@code executeToolCalls} 是从 {@code prompt.getOptions()} 里按名字找工具的，
     * 只在链式调用上声明会导致「模型要调工具、但执行器找不到工具」。
     */
    private final DashScopeChatOptions toolOptions;

    /** 收尾轮用的选项：同样关掉自动执行，但不带任何工具 */
    private final DashScopeChatOptions finalTurnOptions;

    /** 缓存本轮 think 的响应，供同一步的 act 使用 */
    private ChatResponse toolCallChatResponse;

    /** 模型已请求终止，下一轮不再调工具、只输出结论 */
    private boolean terminateRequested = false;

    public ToolCallAgent(ToolCallback[] availableTools) {
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();

        this.toolOptions = DashScopeChatOptions.builder().build();
        this.toolOptions.setInternalToolExecutionEnabled(false);
        this.toolOptions.setToolCallbacks(new ArrayList<>(Arrays.asList(availableTools)));

        this.finalTurnOptions = DashScopeChatOptions.builder().build();
        this.finalTurnOptions.setInternalToolExecutionEnabled(false);
    }

    @Override
    public boolean think() {
        if (terminateRequested) {
            // 收尾轮：不带工具，逼模型把结论写出来
            return doFinalTurn();
        }

        // 每步都提醒模型「你现在该做什么」，避免它跑偏或忘记收尾
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            getMessageList().add(new UserMessage(getNextStepPrompt()));
        }

        try {
            ChatResponse chatResponse = getChatClient()
                    .prompt(new Prompt(getMessageList(), this.toolOptions))
                    .system(getSystemPrompt())
                    .call()
                    .chatResponse();

            this.toolCallChatResponse = chatResponse;

            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();

            log.info("[{}] 模型思考：{}", getName(), StrUtil.maxLength(assistantMessage.getText(), 300));
            log.info("[{}] 决定调用 {} 个工具", getName(), toolCalls.size());
            toolCalls.forEach(call -> log.info("[{}]   -> 工具 {} 参数 {}", getName(), call.name(), call.arguments()));

            if (toolCalls.isEmpty()) {
                // 不调工具时，助手消息需要我们自己记进上下文
                getMessageList().add(assistantMessage);
                return false;
            }
            // 要调工具时不记助手消息：executeToolCalls 会把助手消息 + 工具返回一起补进上下文
            return true;

        } catch (Exception e) {
            log.error("[{}] 思考阶段出错", getName(), e);
            getMessageList().add(new AssistantMessage("思考阶段出错：" + e.getMessage()));
            return false;
        }
    }

    @Override
    public String act() {
        if (toolCallChatResponse == null || !toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }

        Prompt prompt = new Prompt(getMessageList(), this.toolOptions);
        ToolExecutionResult executionResult =
                toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        // 就地覆盖上下文，而不是 setMessageList(新列表)：
        // executeToolCalls 返回的历史有可能是不可变集合，直接替换会让下一轮 add() 抛 UnsupportedOperationException
        getMessageList().clear();
        getMessageList().addAll(executionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage =
                (ToolResponseMessage) CollUtil.getLast(executionResult.conversationHistory());

        // 模型主动调用终止工具 → 本步只做「标记」，下一轮走收尾逻辑
        boolean terminated = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));
        if (terminated) {
            terminateRequested = true;
            getMessageList().add(new UserMessage(FINAL_TURN_PROMPT));
            log.info("[{}] 模型请求终止，进入收尾轮", getName());
        }

        String summary = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回："
                        + StrUtil.maxLength(String.valueOf(response.responseData()), 300))
                .collect(Collectors.joining("\n"));
        log.info("[{}] 工具执行结果：{}", getName(), summary);
        return summary;
    }

    /**
     * 收尾轮：关掉工具再问一次模型，拿最终结论。
     * <p>
     * 返回 false 让 {@link ReActAgent#step()} 把状态置为 FINISHED，结束主循环。
     */
    private boolean doFinalTurn() {
        try {
            ChatResponse chatResponse = getChatClient()
                    .prompt(new Prompt(getMessageList(), this.finalTurnOptions))
                    .system(getSystemPrompt())
                    .call()
                    .chatResponse();

            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            getMessageList().add(assistantMessage);
            log.info("[{}] 收尾轮已输出最终建议，共 {} 字", getName(), StrUtil.length(assistantMessage.getText()));
        } catch (Exception e) {
            log.error("[{}] 收尾轮失败", getName(), e);
            getMessageList().add(new AssistantMessage("收尾失败：" + e.getMessage()));
        }
        return false;
    }

    /** 供子类/测试查看已注册的工具数 */
    public int getToolCount() {
        return availableTools == null ? 0 : availableTools.length;
    }
}
