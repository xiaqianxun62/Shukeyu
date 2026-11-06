package com.yuyue.ai.dto;

import java.util.List;

/**
 * 会话详情：会话元信息 + 按时间正序的消息
 */
public record AiConversationDetail(AiConversationSummary conversation, List<AiMessageItem> messages) {
}
