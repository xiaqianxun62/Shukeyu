package com.yuyue.ai.dto;

import java.util.List;

/**
 * SSE 第一帧：告诉前端「这条会话是谁、参考了哪些规则、查了哪些数据」。
 * <p>
 * 必须在任何 delta 之前发出，前端据此先把引用角标与「正在查询…」的提示渲染出来。
 *
 * @param conversationId 会话 ID（新会话在此返回，前端要保存）
 * @param title          会话标题
 * @param citations      命中的知识库条目，可能为空
 * @param tools          本次调用的数据工具标识（如 MY_RATING），可能为空
 */
public record AiStreamMeta(Long conversationId, String title, List<AiCitation> citations, List<String> tools) {
}
