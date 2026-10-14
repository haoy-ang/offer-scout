package com.haoy.offerscout.controller;

import com.haoy.offerscout.auth.RequireLogin;
import com.haoy.offerscout.match.JobMatch;
import com.haoy.offerscout.match.JobMatchService;
import com.haoy.offerscout.ratelimit.RateLimit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 岗位匹配接口。
 * <p>
 * 用 {@code multipart/form-data} 上传：简历（{@code file} 或 {@code text} 二选一）+
 * 目标岗位 {@code jd}（必填）。返回结构化匹配报告。
 * <p>
 * <b>限流和简历诊断一致（5 次/分钟）</b>：一次匹配 = 简历全文 + JD 全文的 LLM 调用 +
 * 结构化输出，成本高，得防住「同一份 JD 反复点」。
 */
@RestController
@RequestMapping("/ai/match")
@RequireLogin
public class JobMatchController {

    private final JobMatchService jobMatchService;

    public JobMatchController(JobMatchService jobMatchService) {
        this.jobMatchService = jobMatchService;
    }

    /**
     * 匹配简历与岗位。
     *
     * @param file 简历 PDF（可选）
     * @param text 简历文本（可选，作为 PDF 的替代/兜底）
     * @param jd   目标岗位 JD（必填）
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(limit = 5, windowSeconds = 60, message = "岗位匹配比较耗时，每分钟最多 5 次，请稍后再试。")
    public JobMatch analyze(@RequestParam(value = "file", required = false) MultipartFile file,
                            @RequestParam(value = "text", required = false) String text,
                            @RequestParam(value = "jd", required = false) String jd) {
        return jobMatchService.analyze(file, text, jd);
    }
}
