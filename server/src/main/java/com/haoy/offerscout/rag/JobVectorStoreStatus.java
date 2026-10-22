package com.haoy.offerscout.rag;

import org.springframework.stereotype.Component;

/**
 * 知识库（向量库）的就绪状态。
 * <p>
 * <b>为什么需要这个类？</b>原来 {@link JobVectorStoreConfig} 在向量化失败时
 * 只打一行 error 日志、然后把一个空的 {@code SimpleVectorStore} 交给容器 ——
 * 应用照常启动、{@code /health/check} 照常返回 ok，但此后<b>所有检索都返回空</b>，
 * 模型拿不到任何片段，就开始自由发挥。
 * <p>
 * 这是一种最难排查的故障：<b>接口不报错、健康检查全绿、答案突然全错</b>。
 * 把状态收集出来暴露到就绪探针上，才能让"知识库挂了"变成一件看得见的事，
 * 而不是等用户反馈"AI 今天在胡说"。
 * <p>
 * 字段全部 {@code volatile}：写发生在容器启动的单线程里，读发生在任意请求线程，
 * 这里只要求可见性，不需要复合操作的原子性。
 */
@Component
public class JobVectorStoreStatus {

    public enum State {
        /** 还没开始构建（或状态未知） */
        UNKNOWN,
        /** 正在调 embedding 接口做全量向量化 */
        BUILDING,
        /** 命中本地缓存，直接 load，没有重复向量化 */
        READY_FROM_CACHE,
        /** 本次真正做了全量向量化并落盘 */
        READY_BUILT,
        /** 素材目录为空 —— 不是故障，但 RAG 一定什么都不返回 */
        EMPTY,
        /** 构建失败 —— 通常是 API Key 无效 / 网络不通 / 模型名写错 */
        FAILED
    }

    private volatile State state = State.UNKNOWN;
    /** 索引里的片段数。为 0 且 state=EMPTY/FAILED 时，检索必然返回空 */
    private volatile int chunks;
    /** 构建耗时（毫秒）；命中缓存时接近 0 */
    private volatile long buildMillis;
    /** 面向排障的一句话说明（失败的根因、缓存文件名等） */
    private volatile String message = "";

    void mark(State state, int chunks, long buildMillis, String message) {
        this.state = state;
        this.chunks = chunks;
        this.buildMillis = buildMillis;
        this.message = message == null ? "" : message;
    }

    /** 检索是否真的可用（只有这两种状态能保证里面有向量） */
    public boolean isSearchable() {
        return (state == State.READY_FROM_CACHE || state == State.READY_BUILT) && chunks > 0;
    }

    public State getState() {
        return state;
    }

    public int getChunks() {
        return chunks;
    }

    public long getBuildMillis() {
        return buildMillis;
    }

    public String getMessage() {
        return message;
    }
}
