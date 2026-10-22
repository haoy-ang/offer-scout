package com.haoy.offerscout.controller;

import com.haoy.offerscout.auth.RequireLogin;
import com.haoy.offerscout.observability.TokenUsageScene;
import com.haoy.offerscout.ratelimit.RateLimit;
import com.haoy.offerscout.resume.ResumeDiagnosis;
import com.haoy.offerscout.resume.ResumeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历诊断接口。
 * <p>
 * 用 {@code multipart/form-data} 上传：要么传 {@code file}（PDF），要么传 {@code text}（粘贴文本），
 * 二选一，都传时优先用文件。{@code targetJob} 可选，填了会让诊断围绕目标岗位展开。
 * <p>
 * <b>限流比普通对话更紧（5 次/分钟）</b>：一次诊断 = 一次长上下文的 LLM 调用
 * （简历全文 + 知识库参考 + 结构化输出），成本高、响应慢，得防住"反复点"。
 */
@RestController
@RequestMapping("/ai/resume")
@RequireLogin
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    /**
     * 分析简历并返回结构化诊断。
     *
     * @param file      简历 PDF（可选）
     * @param text      直接粘贴的简历文本（可选，作为 PDF 的替代/兜底）
     * @param targetJob 目标岗位描述（可选）
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(limit = 5, windowSeconds = 60, dimension = RateLimit.Dimension.USER,
            message = "简历诊断比较耗时，每分钟最多 5 次，请稍后再试。")
    public ResumeDiagnosis analyze(@RequestParam(value = "file", required = false) MultipartFile file,
                                   @RequestParam(value = "text", required = false) String text,
                                   @RequestParam(value = "targetJob", required = false) String targetJob) {
        try (var ignored = TokenUsageScene.open("简历诊断")) {
            return resumeService.analyze(file, text, targetJob);
        }
    }
}
