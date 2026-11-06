package com.yuyue.ai.kb;

/**
 * 一次检索命中的分片。
 *
 * @param chunk 命中的分片
 * @param score 相关度得分，仅用于排序，不对外暴露
 */
public record AiKnowledgeHit(AiKnowledgeChunk chunk, double score) {
}
