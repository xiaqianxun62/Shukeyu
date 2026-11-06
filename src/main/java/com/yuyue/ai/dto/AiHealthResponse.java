package com.yuyue.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 健康检查结果。
 * <p>
 * 只暴露「是否就绪」这类布尔与名称信息，绝不返回 API Key 或完整异常堆栈。
 */
@Data
@Builder
public class AiHealthResponse {

    /** 业务总开关 yuyue.ai.enabled */
    private Boolean enabled;

    /** 供应商标识（openai 兼容协议统一为 openai） */
    private String provider;

    /** 聊天模型名 */
    private String chatModel;

    /** 容器中是否存在 ChatModel（=Spring AI 自动装配成功） */
    private Boolean chatModelPresent;

    /** 是否配置了 API Key（只判断非空，不回显内容） */
    private Boolean apiKeyConfigured;

    /** UP 可用 / DISABLED 未启用 / UNCONFIGURED 缺配置 / DOWN 探活失败 */
    private String status;

    /** ping=true 时真实调用的耗时（毫秒），未探活为 null */
    private Long latencyMs;

    /** 面向运维的一句话说明 */
    private String message;

    /** 检查时间 */
    private LocalDateTime checkedAt;
}
