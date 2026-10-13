package com.haoy.offerscout.agent;

import cn.hutool.core.util.StrUtil;
import com.haoy.offerscout.agent.model.AgentState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 抽象基础代理类：管理执行状态和「按步执行」的主循环。
 * <p>
 * 子类只需要实现 {@link #step()} 定义「一步做什么」。
 * <p>
 * <b>线程安全提醒</b>：本类的 {@code state}、{@code currentStep}、{@code messageList} 都是可变字段，
 * 一个实例只能服务一次请求。千万不要把 Agent 注册成单例 Bean 后并发调用，
 * 否则两个请求会互相踩状态 —— 正确做法是把它声明为 prototype 作用域，每次请求取一个新实例。
 */
@Slf4j
public abstract class BaseAgent {

    /** 代理名称，只用于日志可读性 */
    @Getter
    @Setter
    private String name = "OfferScoutAgent";

    /** 系统提示词：定义角色与工作方式 */
    @Getter
    @Setter
    private String systemPrompt;

    /** 每一步注入给模型的提醒（下一步该干什么） */
    @Getter
    @Setter
    private String nextStepPrompt;

    /** 当前状态 */
    @Getter
    private AgentState state = AgentState.IDLE;

    /** 已执行的步数 */
    @Getter
    private int currentStep = 0;

    /** 最大步数，防止模型陷入死循环白烧 token */
    @Getter
    @Setter
    private int maxSteps = 10;

    /** 大模型客户端 */
    @Getter
    @Setter
    private ChatClient chatClient;

    /**
     * 会话上下文。Agent 需要自己维护消息列表：
     * 因为绕过 Spring AI 内置的工具执行后，框架不再替我们记录工具调用往返的消息。
     */
    @Getter
    private final List<Message> messageList = new ArrayList<>();

    /** 每一步的执行摘要，便于前端展示 agent 的思考过程 */
    private final List<String> stepLog = new ArrayList<>();

    /** 每步回调，用于 SSE 逐步推送（可选） */
    @Setter
    private Consumer<String> stepListener;

    /**
     * 执行代理。
     *
     * @param userPrompt 用户任务描述
     * @return 最终回答（取最后一条非空的助手消息）
     */
    public String run(String userPrompt) {
        if (state != AgentState.IDLE) {
            throw new IllegalStateException("代理当前状态为 " + state + "，不能重复执行；请为每次请求创建新的实例");
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new IllegalArgumentException("任务描述不能为空");
        }

        state = AgentState.RUNNING;
        messageList.add(new UserMessage(userPrompt));

        try {
            for (int i = 0; i < maxSteps && state == AgentState.RUNNING; i++) {
                currentStep = i + 1;
                log.info("[{}] 执行第 {}/{} 步", name, currentStep, maxSteps);

                String stepResult = step();
                String line = "第 " + currentStep + " 步｜" + stepResult;
                stepLog.add(line);
                if (stepListener != null) {
                    stepListener.accept(line);
                }
            }

            if (state == AgentState.RUNNING) {
                // 跑满 maxSteps 还没结束，兜底收尾，避免状态卡在 RUNNING
                log.warn("[{}] 达到最大步数 {}，强制结束", name, maxSteps);
                state = AgentState.FINISHED;
            }
            return getFinalAnswer();

        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("[{}] 执行失败", name, e);
            return "执行出错：" + e.getMessage();
        } finally {
            cleanup();
        }
    }

    /**
     * 取出最终回答：消息列表里最后一条有内容的助手消息。
     * <p>
     * 带工具调用的助手消息文本可能是空的（只有 tool_calls），所以要跳过空文本往前找。
     */
    protected String getFinalAnswer() {
        for (int i = messageList.size() - 1; i >= 0; i--) {
            Message message = messageList.get(i);
            if (message instanceof AssistantMessage assistant && StrUtil.isNotBlank(assistant.getText())) {
                return assistant.getText();
            }
        }
        // 兜底：至少把过程还给调用方，别返回空
        return StrUtil.join("\n", stepLog);
    }

    /** 每个步骤做些什么，由子类实现 */
    public abstract String step();

    /** 设置状态，供子类（如识别到终止工具）修改 */
    protected void setState(AgentState state) {
        this.state = state;
    }

    /** 资源清理钩子，子类可重写 */
    protected void cleanup() {
        // 默认什么都不做
    }

    /** 供接口层拿到完整的步骤日志 */
    public List<String> getStepLog() {
        return List.copyOf(stepLog);
    }
}
