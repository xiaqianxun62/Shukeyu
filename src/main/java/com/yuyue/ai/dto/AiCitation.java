package com.yuyue.ai.dto;

/**
 * 知识库引用：回答里的 [1] 对应这里的 index。
 *
 * @param index 引用序号，从 1 开始
 * @param title 知识点标题，如「ELO 积分规则 · K 值：按总场次分档」
 * @param doc   所属文档名，前端可据此分组展示
 */
public record AiCitation(int index, String title, String doc) {
}
