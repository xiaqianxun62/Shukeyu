package com.yuyue.ai.kb;

/**
 * 知识库分片：以 Markdown 的一级标题作为「文档」，二级标题切成分片。
 *
 * @param id       稳定标识，格式「文件名#序号」，用于引用与去重
 * @param doc      文档名，如「ELO 积分规则」
 * @param title    分片标题，如「K 值：按总场次分档」
 * @param keywords 文档级关键词（逗号分隔），命中即高分，是检索的主要抓手
 * @param content  正文（不含标题行），已按上限截断
 */
public record AiKnowledgeChunk(String id, String doc, String title, String keywords, String content) {

    /** 引用展示用标题，如「ELO 积分规则 · K 值：按总场次分档」 */
    public String displayTitle() {
        return doc.equals(title) ? doc : doc + " · " + title;
    }
}
