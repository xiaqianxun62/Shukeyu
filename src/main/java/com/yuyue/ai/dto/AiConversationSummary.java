package com.yuyue.ai.dto;

import java.time.LocalDateTime;

/**
 * 会话列表项
 */
public record AiConversationSummary(
        Long id,
        String title,
        Integer messageCount,
        LocalDateTime lastMessageAt,
        LocalDateTime createTime) {
}
