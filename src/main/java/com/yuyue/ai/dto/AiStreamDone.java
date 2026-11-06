package com.yuyue.ai.dto;

/**
 * SSE 结束帧：回答已落库，前端可以刷新会话列表。
 *
 * @param conversationId 会话 ID
 * @param messageId      回答消息 ID；落库失败时为 null（内容已在流里给过，不影响展示）
 * @param totalTokens    token 数，流式下常为 0
 * @param latencyMs      端到端耗时
 */
public record AiStreamDone(Long conversationId, Long messageId, int totalTokens, long latencyMs) {
}
