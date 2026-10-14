package com.haoy.offerscout.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口。
 * <p>
 * 用于确认服务已经正常启动，也方便部署平台做存活探针。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping("/check")
    public String healthCheck() {
        return "ok";
    }
}
