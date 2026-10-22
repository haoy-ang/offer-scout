package com.haoy.offerscout.observability;

/**
 * 当前调用所属的业务场景（请求级）。
 * <p>
 * <b>为什么需要传场景？</b>光有一个总数回答不了"哪个功能最贵" —— 而后者才是能指导优化的信息。
 * 场景信息只有业务代码知道（模型装饰器在下面一层，看不见调用方是谁），所以用它把上下文传下去。
 * <p>
 * <b>⚠️ ThreadLocal 的两个必踩点，这里都处理了：</b>
 * <ol>
 *   <li><b>用完必须还原。</b>Tomcat 线程会复用，不清理的话下一次请求会被记到上一个场景名下。
 *       这里用 try-with-resources + 保存/恢复上一个值实现（支持嵌套），不依赖调用方记得写 finally。</li>
 *   <li><b>不跨线程。</b>Agent 任务跑在专用线程池里、流式回调跑在 Reactor 线程 ——
 *       这些地方读不到请求线程上的值。<b>所以 {@code open()} 必须写在真正发起模型调用的那个线程上</b>：
 *       Agent 场景写在 {@code agentExecutor} 的任务体里，而不是 Controller 方法开头。</li>
 * </ol>
 * <b>为什么不用 MDC？</b>MDC 也能存，但要在 logback 的 pattern 里加 {@code %X{scene}} 才会输出，
 * 而这里的数据是要被记录器主动读取、参与聚合的，不是只给日志排版用 —— 直接 ThreadLocal 更可控。
 *
 * <pre>{@code
 * try (var ignored = TokenUsageScene.open("简历诊断")) {
 *     ... // 这里面发生的模型调用都会被记到这个场景
 * }
 * }</pre>
 */
public final class TokenUsageScene implements AutoCloseable {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 进入前的场景名，close 时还原（支持嵌套调用，不丢外层） */
    private final String previous;

    private TokenUsageScene(String previous) {
        this.previous = previous;
    }

    /**
     * 标记当前线程接下来发生的大模型调用属于哪个场景。
     *
     * @param scene 场景名，例如「智能问答」「简历诊断」「岗位匹配」「模拟面试」「作战规划」
     */
    public static TokenUsageScene open(String scene) {
        String prev = CURRENT.get();
        CURRENT.set(scene);
        return new TokenUsageScene(prev);
    }

    /** 当前场景；没设置时返回 null（记录器会记为 unknown，不会 NPE） */
    public static String current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
