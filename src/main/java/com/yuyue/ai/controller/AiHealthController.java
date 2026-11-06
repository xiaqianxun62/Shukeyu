package com.yuyue.ai.controller;

import com.yuyue.ai.dto.AiHealthResponse;
import com.yuyue.ai.service.AiHealthService;
import com.yuyue.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 健康检查：给运维 / 联调用，确认 Spring AI 是否接上模型。
 * 公开接口（不校验登录），只返回就绪状态，不含任何密钥信息。
 */
@RestController
@RequestMapping("ai")
@RequiredArgsConstructor
@Tag(name = "AI 智能服务", description = "Spring AI 接入状态与健康检查")
public class AiHealthController {

    private final AiHealthService aiHealthService;

    @Operation(
            summary = "AI 健康检查",
            description = "返回 AI 总开关、ChatModel 是否存在、API Key 是否配置；ping=true 时真实调用一次模型（极少量 token）"
    )
    @GetMapping("health")
    public ApiResponse<AiHealthResponse> health(
            @Parameter(description = "是否发起一次真实模型调用") @RequestParam(defaultValue = "false") boolean ping) {
        return ApiResponse.ok(aiHealthService.check(ping));
    }
}
