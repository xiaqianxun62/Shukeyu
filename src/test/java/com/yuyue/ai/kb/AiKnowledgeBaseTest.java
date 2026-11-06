package com.yuyue.ai.kb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识库是「规则答疑」的地基：检索不到就等于模型在裸答，所以这几条必须成立。
 */
class AiKnowledgeBaseTest {

    private AiKnowledgeBase kb;

    @BeforeEach
    void setUp() {
        kb = new AiKnowledgeBase();
        kb.load();
    }

    @Test
    @DisplayName("启动加载：六个文档都能切成非空分片，且带关键词")
    void loadsAllDocs() {
        List<AiKnowledgeChunk> chunks = kb.chunks();
        assertTrue(chunks.size() >= 20, "分片数偏少，实际=" + chunks.size());
        assertEquals(6, chunks.stream().map(AiKnowledgeChunk::doc).distinct().count());
        assertTrue(chunks.stream().allMatch(c -> !c.content().isBlank()));
        assertTrue(chunks.stream().allMatch(c -> !c.keywords().isBlank()), "每个文档都要有关键词行");
    }

    @Test
    @DisplayName("积分问题：命中 ELO 文档，且 K 值问题能精确落到对应小节")
    void findsEloChunks() {
        List<AiKnowledgeHit> hits = kb.search("双打积分怎么算", 3);
        assertFalse(hits.isEmpty());
        assertEquals("ELO 积分规则", hits.get(0).chunk().doc());

        List<AiKnowledgeHit> kHits = kb.search("K值分档是多少", 3);
        assertFalse(kHits.isEmpty());
        assertEquals("ELO 积分规则", kHits.get(0).chunk().doc());
        assertTrue(kHits.get(0).chunk().displayTitle().contains("K 值"),
                "实际=" + kHits.get(0).chunk().displayTitle());
    }

    @Test
    @DisplayName("轮排与赛事问题：各自命中对应文档")
    void findsDomainChunks() {
        assertEquals("轮排与自动编排", kb.search("男女分场的轮排怎么排", 2).get(0).chunk().doc());
        assertEquals("赛事（锦标赛）", kb.search("赛事抽签后还能改报名吗", 2).get(0).chunk().doc());
        assertEquals("账号与权限", kb.search("忘记密码怎么改", 2).get(0).chunk().doc());
    }

    @Test
    @DisplayName("无关问题：不硬凑分片，返回空（宁可不答也不给错规则）")
    void returnsEmptyForUnrelatedQuestion() {
        assertTrue(kb.search("今天食堂吃什么", 3).isEmpty());
        assertTrue(kb.search("帮我写一首诗", 3).isEmpty());
        assertTrue(kb.search("", 3).isEmpty());
        assertTrue(kb.search(null, 3).isEmpty());
    }

    @Test
    @DisplayName("中文二元词：长句能切出可用词，疑问词被过滤")
    void tokenizesChinese() {
        List<String> terms = kb.terms("请问我的双打积分怎么算");
        assertTrue(terms.contains("积分"));
        assertTrue(terms.contains("双打"));
        assertFalse(terms.contains("怎么"));
        assertFalse(terms.contains("我的"));
    }

    @Test
    @DisplayName("Markdown 切分：一级标题做文档名，二级标题切片，关键词行不进入正文")
    void parsesMarkdown() {
        String md = """
                # 测试文档
                关键词：测试, 样例
                ## 小节一
                内容一
                ## 小节二
                内容二
                """;
        List<AiKnowledgeChunk> chunks = kb.parse("sample", md);
        assertEquals(2, chunks.size());
        assertEquals("测试文档", chunks.get(0).doc());
        assertEquals("小节一", chunks.get(0).title());
        assertTrue(chunks.get(0).content().contains("内容一"));
        assertFalse(chunks.get(0).content().contains("关键词"));
        assertEquals("测试文档 · 小节二", chunks.get(1).displayTitle());
    }
}
