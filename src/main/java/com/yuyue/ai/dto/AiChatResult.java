package com.yuyue.ai.dto;

/**
 * 一次模型调用的结果。
 * <p>
 * 只带业务层需要的东西：回复正文、实际模型名、token 计量与耗时。
 * 降级/失败不走这个对象，而是由 {@link com.yuyue.ai.gateway.AiGateway} 抛业务异常，
 * 调用方据此展示「AI 暂时不可用，已切到内置方案」。
 *
 * @param content          模型回复正文（可能为空串，调用方需自行判空）
 * @param model            实际模型名
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param totalTokens      合计 token 数
 * @param latencyMs        端到端耗时（毫秒）
 */
public record AiChatResult(
        String content,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long latencyMs) {
}
