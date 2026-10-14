package com.haoy.offerscout.agent.model;

/**
 * 代理（Agent）的执行状态。
 * <p>
 * 状态机是 Agent 循环的骨架：只有 IDLE 才能接受新任务，
 * 循环每跑一步都要检查状态，只有 FINISHED 才提前退出。
 */
public enum AgentState {

    /** 空闲：可以接受新任务 */
    IDLE,

    /** 运行中：正在执行 think / act 循环 */
    RUNNING,

    /** 已完成：任务结束（正常结束或调用终止工具结束） */
    FINISHED,

    /** 出错：循环中抛出未处理异常 */
    ERROR
}
