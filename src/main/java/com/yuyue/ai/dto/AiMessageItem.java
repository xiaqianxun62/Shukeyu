package com.yuyue.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史消息项
 *
 * @param role       USER / ASSISTANT
 * @param content    正文
 * @param citations  引用条目（已格式化为「[1] 标题」），提问行恒为空
 * @param tools      本次回答用到的数据工具标识
 * @param createTime 发送时间
 */
public record AiMessageItem(
        Long id,
        String role,
        String content,
        List<String> citations,
        List<String> tools,
        Integer totalTokens,
        Integer latencyMs,
        LocalDateTime createTime) {
}
