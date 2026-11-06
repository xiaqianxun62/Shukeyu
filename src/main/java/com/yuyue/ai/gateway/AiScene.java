package com.yuyue.ai.gateway;

/**
 * AI 业务场景：既是提示词与参数的分类，也是用量统计的维度。
 * <p>
 * 场景值是写进 {@code ai_usage_log.scene} 的稳定标识，改名等于改数据，新增时只追加。
 */
public enum AiScene {

    /** 智能问答（规则答疑 + 数据查询） */
    CHAT,

    /** 个性化训练计划 */
    TRAINING_PLAN,

    /** 球局匹配理由 */
    MATCH_SUGGEST,

    /** 社团运营月报 */
    REPORT
}
