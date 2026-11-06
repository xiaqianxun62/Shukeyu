package com.yuyue.ai.dto;

/**
 * SSE 增量帧：一段模型输出，前端直接 append 到气泡里即可。
 *
 * @param text 增量文本，可能含换行
 */
public record AiStreamDelta(String text) {
}
