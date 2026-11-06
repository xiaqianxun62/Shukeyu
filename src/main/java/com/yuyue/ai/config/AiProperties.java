package com.yuyue.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 相关配置（前缀 yuyue.ai）。
 * <p>
 * 与 Spring AI 自身的 spring.ai.* 分工：
 * <ul>
 *   <li>spring.ai.model.chat / spring.ai.openai.* 决定「能不能连上模型」（由 Spring AI 自动装配读取）；</li>
 *   <li>yuyue.ai.* 决定「业务侧怎么用模型」（总开关、并发、配额、超时策略）。</li>
 * </ul>
 * 两者都满足时 AI 才可用：yuyue.ai.enabled=true 且容器中存在 ChatModel。
 */
@Data
@Component
@ConfigurationProperties(prefix = "yuyue.ai")
public class AiProperties {

    /**
     * 业务总开关。
     * false 时不发起任何模型调用，AI 接口一律返回「未启用」，主业务完全不受影响。
     */
    private boolean enabled = false;

    /** 模型供应商标识，仅用于展示与计量（OpenAI 兼容协议下统一走 openai starter） */
    private String provider = "openai";

    /** 聊天模型名，仅用于展示；实际模型由 spring.ai.openai.chat.options.model 决定 */
    private String chatModel = "deepseek-chat";

    /** 是否允许 /ai/health?ping=true 发起真实模型调用（会产生极少量 token 费用） */
    private boolean healthPingEnabled = true;

    /**
     * 单次调用超时（秒）：网关用 Future.get 兜底，超时立即返回并让调用方降级。
     * HTTP 层还有 spring.http.client.read-timeout 兜底，两者是「业务超时」与「连接超时」的分工。
     */
    private int timeoutSeconds = 20;

    /** 并发上限：超过后快速失败并降级，避免长耗时请求占满 Tomcat 线程池 */
    private int maxConcurrency = 8;

    /** 每用户每日调用次数上限，0 表示不限制 */
    private int dailyQuota = 30;

    /** 熔断阈值：连续失败多少次后暂停调用 */
    private int failureThreshold = 5;

    /** 熔断冷却时间（秒）：冷却结束放一个请求试探，成功即恢复 */
    private int openSeconds = 60;

    /** 单次业务调用的最多尝试次数（含首次），1 表示不重试 */
    private int maxAttempts = 2;

    /** 重试前等待（毫秒），避免瞬时抖动时连续打上游 */
    private long retryBackoffMs = 800;
}
