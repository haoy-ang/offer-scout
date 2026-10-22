package com.haoy.offerscout.controller;

import com.haoy.offerscout.auth.RequireLogin;
import com.haoy.offerscout.observability.TokenUsageRecorder;
import com.haoy.offerscout.ratelimit.RateLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 成本观测接口：看一眼模型调用花了多少 token、哪个功能最贵。
 * <p>
 * <b>为什么这个接口值得存在？</b>之前项目里没有任何 token 统计，等于"按量计费的系统
 * 却看不到账单"。有了它，改 prompt、换模型、调 topK 之后可以直接对比成本变化，
 * 而不是凭感觉说"应该差不多"。
 * <p>
 * <b>数据口径的限制必须说清楚</b>（面试问到时这就是加分点）：
 * <ul>
 *   <li>计数在<b>内存</b>里，进程重启归零；多实例各算一份，不是全局总量；</li>
 *   <li>流式调用如果供应商没在末尾 chunk 返回 usage，就只记得到次数和耗时，
 *       {@code usageMissingCalls} 会涨 —— 这个数应该尽量小，涨起来说明统计不可信。</li>
 * </ul>
 * 要做真正的成本报表，得改成"每次调用落一行、按天聚合"，那是另一个量级的投入。
 * <p>
 * <b>权限：</b>只要求登录。个人项目里这就是自己看账，没做管理员角色；
 * 多租户场景应该加管理员校验，否则等于把成本数据公开给所有用户。
 */
@RestController
@RequestMapping("/ops")
@RequireLogin
public class UsageController {

    private final TokenUsageRecorder recorder;

    public UsageController(TokenUsageRecorder recorder) {
        this.recorder = recorder;
    }

    /** 全局 / 按场景 / 按模型的用量快照，附最近 20 条明细 */
    @GetMapping("/usage")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public Map<String, Object> usage() {
        return recorder.snapshot();
    }
}
