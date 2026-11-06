package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 调用计量：每次真正打到模型的调用一行（成功、失败都记）。
 * <p>
 * 只记用量与耗时，不记提示词与回复原文——原文在会话表里，这里只服务于
 * 「花了多少 token / 哪个场景最费钱 / 失败率高不高」。
 */
@Data
@TableName("ai_usage_log")
public class AiUsageLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调用者用户 ID；系统任务（如月报）为 NULL */
    private Long userId;

    /** 业务场景：CHAT / TRAINING_PLAN / MATCH_SUGGEST / REPORT */
    private String scene;

    /** 供应商（openai 兼容协议下统一为 openai） */
    private String provider;

    /** 实际模型名：优先取上游返回，取不到时回退配置值 */
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 端到端耗时（毫秒，含重试） */
    private Integer latencyMs;

    /** 1 成功 0 失败 */
    private Integer success;

    /** 失败时的异常类型 / HTTP 状态码摘要 */
    private String errorType;

    private LocalDateTime createTime;
}
