package com.haoy.offerscout.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 结构化输出的兜底重试。
 * <p>
 * 本项目有三个功能依赖大模型吐 JSON（简历诊断 / 岗位匹配 / 面试复盘），
 * 靠的是 {@code ChatClient.call().entity(X.class)}。它有三个失败模式，都不是"代码写错了"：
 * <ol>
 *   <li>模型多包了一层 <code>```json</code> 围栏，或前后带了"好的，以下是结果："之类的话；</li>
 *   <li>字段类型飘了 —— 该给整数给了字符串（这正是我们把评分设计成 {@code int} 的原因，但拦不住全部）；</li>
 *   <li>能反序列化，但内容残缺（比如 {@code dimensions} 是个空数组）。</li>
 * </ol>
 * 前两种会抛异常，第三种最阴 —— 它<b>静默成功</b>，前端渲染出一片空白，比直接报错更难排查。
 * <p>
 * <b>为什么要重试？</b>这是「调大模型的应用」和「调普通 API 的应用」最大的区别：
 * 下游不是确定性的。同一个提示词，模型这次吐对的、下次可能就吐错了。
 * 重试是成本最低、收益最高的稳定性手段 —— 一次重试就能覆盖掉绝大多数偶发格式错误。
 * <p>
 * <b>为什么要设上限？</b>每次重试都是一次<b>真金白银的模型调用</b>。
 * 无上限重试会在模型持续抽风时把额度烧光，所以这里最多 3 次（1 次原始 + 2 次重试），
 * 最坏情况是 3 倍成本 —— 这个代价是可接受的，再高就不划算了。
 * <p>
 * <b>为什么带退避？</b>失败若是上游瞬时限流/抖动引起的，立刻重试大概率再失败一次。
 * 隔一小段时间再打，成功率更高（代价是接口多等几百毫秒，这个场合可以接受）。
 */
@Slf4j
public final class StructuredOutputRetry {

    /** 最多尝试次数：1 次原始调用 + 2 次重试。每次都是真调用，别再往上加。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 退避基数（毫秒），第 n 次失败后等 n × 该值 */
    private static final long BACKOFF_MILLIS = 400L;

    private StructuredOutputRetry() {
    }

    /**
     * 反复调用直到拿到"可用"的结果。
     *
     * @param scene  场景名，只用于日志和最终报错文案（如「简历诊断」）
     * @param call   真正发起模型调用并反序列化的动作
     * @param usable 完整性校验：返回 false 视为本次结果不可用，触发重试。
     *               传 {@code r -> true} 表示只靠异常判断成败。
     * @return 第一个通过校验的结果
     * @throws BusinessException 连续 {@value #MAX_ATTEMPTS} 次都没拿到可用结果
     */
    public static <T> T call(String scene, Supplier<T> call, Predicate<T> usable) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                T result = call.get();
                if (result != null && usable.test(result)) {
                    if (attempt > 1) {
                        log.info("{}：第 {} 次尝试终于拿到可用结果（前 {} 次未通过）",
                                scene, attempt, attempt - 1);
                    }
                    return result;
                }
                log.warn("{}：第 {} 次输出内容不完整（能反序列化但关键字段缺失），准备重试", scene, attempt);
            } catch (BusinessException e) {
                // 业务语义错误（参数不合法、越权、下游 4xx）重试一万次结果也一样，
                // 而每次重试都是一次真实计费的模型调用 —— 直接上抛，不兜。
                throw e;
            } catch (RuntimeException e) {
                if (isCodeBug(e)) {
                    // NPE / 类型转换 / 越界：这是"我们自己的代码写错了"，不是"模型这次没写对"。
                    // 吞掉重试只会把 bug 伪装成"模型不稳定"，把排错方向彻底带偏 —— 宁可炸出来。
                    throw e;
                }
                // 单次失败不往外抛：重试的意义就在于把偶发失败吃掉。
                // 真正的失败在循环外统一抛，保证"抛出去的一定是连续 N 次都失败"。
                log.warn("{}：第 {} 次结构化输出失败（{}），准备重试", scene, attempt, e.getMessage());
            }
            backoff(attempt);
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                scene + "失败：模型连续 " + MAX_ATTEMPTS + " 次都没有返回可用的结构化结果，请稍后重试。");
    }

    /**
     * 区分「代码 bug」和「模型输出不符合预期」。
     * <p>
     * 判据很简单：下面这三类异常<b>不可能由 JSON 格式差异产生</b>。
     * 一旦出现，说明是我们自己代码的空值判断 / 类型假设错了，
     * 这时候重试三次再把 500 抛给用户，只会让真正的 bug 藏得更深。
     */
    private static boolean isCodeBug(RuntimeException e) {
        return e instanceof NullPointerException
                || e instanceof ClassCastException
                || e instanceof IndexOutOfBoundsException;
    }

    /** 第 n 次失败后等 n 个退避单位；最后一次失败后不必再等 */
    private static void backoff(int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return;
        }
        try {
            Thread.sleep(BACKOFF_MILLIS * attempt);
        } catch (InterruptedException e) {
            // 恢复中断标志：吞掉 InterruptedException 会让上层线程池无法正确停机
            Thread.currentThread().interrupt();
        }
    }
}
