package com.yuyue.ai.kb;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则知识库：启动时把 {@code classpath:ai/knowledge/*.md} 读进内存并切分，运行时做关键词检索。
 * <p>
 * 为什么不上向量库：规则条目只有几十条、且更新时跟着代码一起发版。向量化要额外引一套
 * embedding + 向量存储，检索质量对这个规模并没有数量级提升，反而多两个会挂的依赖。
 * 这里用「关键词命中 + 中文二元词重合率」打分，命中率对本场景足够，且零外部依赖、可单测。
 * <p>
 * 切分规则：{@code # 文档标题} 定义文档，行首 {@code 关键词：} 定义该文档的关键词，
 * 每个 {@code ## 小节} 切一个分片。检索命中后按分片返回，引用可以精确到小节。
 */
@Slf4j
@Component
public class AiKnowledgeBase {

    private static final String LOCATION = "classpath*:ai/knowledge/*.md";
    /** 单条分片上限：超过就截断，避免个别长文档把提示词预算吃光 */
    private static final int CHUNK_MAX_CHARS = 900;
    /** 命中阈值：低于它视为「知识库没有相关内容」，宁可不答也不硬凑 */
    private static final double MIN_SCORE = 3.0;

    private static final Pattern HAN_RUN = Pattern.compile("\\p{IsHan}+");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z][a-z0-9_]{1,}");
    private static final Pattern NOISE = Pattern.compile("[^\\p{IsHan}a-z0-9]+");

    /**
     * 疑问词与虚词：它们几乎出现在每句话里，参与打分只会让噪声分片浮上来。
     * 只过滤「整词」与二元词，不影响真实关键词（如「积分」）。
     */
    private static final Set<String> STOP_TERMS = Set.of(
            "什么", "怎么", "怎样", "如何", "为什么", "可以", "能否", "是否", "请问", "一下",
            "我的", "我们", "你们", "这个", "那个", "这些", "那些", "多少", "哪些", "哪个",
            "告诉", "介绍", "知道", "帮我", "我想", "想问", "现在", "今天", "最近", "有没",
            "没有", "是不", "不是", "有哪", "的话", "一个", "以及", "还有", "就是", "应该");

    private final List<AiKnowledgeChunk> chunks = new ArrayList<>();

    @PostConstruct
    public void load() {
        List<AiKnowledgeChunk> loaded = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(LOCATION);
            // 文件名排序，保证每次启动的分片顺序与引用编号稳定
            List<Resource> ordered = new ArrayList<>(List.of(resources));
            ordered.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource resource : ordered) {
                String filename = String.valueOf(resource.getFilename());
                if (!resource.isReadable()) {
                    log.warn("知识库文件不可读，已跳过: {}", filename);
                    continue;
                }
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                loaded.addAll(parse(filename.replace(".md", ""), text));
            }
        } catch (IOException e) {
            // 知识库是增强能力，读不到就退化成「只靠数据工具 + 模型常识」，不能让应用起不来
            log.error("加载 AI 知识库失败，问答将不带规则上下文: {}", e.getMessage());
        }
        chunks.clear();
        chunks.addAll(loaded);
        log.info("AI 知识库加载完成：{} 条分片（{} 个文件）", chunks.size(),
                chunks.stream().map(AiKnowledgeChunk::doc).distinct().count());
    }

    /** 解析单个 Markdown 文件：见类注释里的切分规则 */
    List<AiKnowledgeChunk> parse(String slug, String text) {
        List<AiKnowledgeChunk> result = new ArrayList<>();
        String docTitle = slug;
        String keywords = "";
        String sectionTitle = null;
        StringBuilder body = new StringBuilder();
        int index = 0;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.startsWith("# ")) {
                docTitle = line.substring(2).strip();
                continue;
            }
            if (line.startsWith("关键词：") || line.startsWith("关键词:")) {
                keywords = line.substring(4).strip();
                continue;
            }
            if (line.startsWith("## ")) {
                index = flush(result, slug, docTitle, keywords, sectionTitle, body, index);
                sectionTitle = line.substring(3).strip();
                continue;
            }
            body.append(rawLine).append('\n');
        }
        flush(result, slug, docTitle, keywords, sectionTitle, body, index);
        return result;
    }

    private int flush(List<AiKnowledgeChunk> result, String slug, String docTitle, String keywords,
                      String sectionTitle, StringBuilder body, int index) {
        String content = body.toString().strip();
        body.setLength(0);
        if (content.isEmpty()) {
            return index;
        }
        if (content.length() > CHUNK_MAX_CHARS) {
            content = content.substring(0, CHUNK_MAX_CHARS) + "\n…";
        }
        String title = sectionTitle == null ? docTitle : sectionTitle;
        result.add(new AiKnowledgeChunk(slug + "#" + index, docTitle, title, keywords, content));
        return index + 1;
    }

    /** 全部已加载分片（只读快照），供测试与诊断使用 */
    public List<AiKnowledgeChunk> chunks() {
        return List.copyOf(chunks);
    }

    /**
     * 检索与问题最相关的规则分片。
     *
     * @param question 用户原话
     * @param limit    最多返回几条（建议 3，多了会挤占提示词预算）
     * @return 按相关度倒序；无相关内容时返回空列表
     */
    public List<AiKnowledgeHit> search(String question, int limit) {
        if (question == null || question.isBlank() || chunks.isEmpty() || limit <= 0) {
            return List.of();
        }
        String normalized = normalize(question);
        List<String> terms = terms(normalized);
        if (terms.isEmpty()) {
            return List.of();
        }

        List<AiKnowledgeHit> hits = new ArrayList<>();
        for (AiKnowledgeChunk chunk : chunks) {
            double score = score(normalized, terms, chunk);
            if (score >= MIN_SCORE) {
                hits.add(new AiKnowledgeHit(chunk, score));
            }
        }
        hits.sort(Comparator.comparingDouble(AiKnowledgeHit::score).reversed());
        return hits.size() <= limit ? hits : hits.subList(0, limit);
    }

    /**
     * 打分 = 关键词命中（强信号）+ 本次提问二元词在分片中的覆盖率 + 标题词命中。
     * 只做加法不归一化：命中关键词的分片本来就该压过「碰巧有几个字相同」的分片。
     */
    private double score(String normalizedQuestion, List<String> terms, AiKnowledgeChunk chunk) {
        String haystack = normalize(chunk.title() + "\n" + chunk.content());
        double keywordHits = 0;
        for (String keyword : chunkKeywords(chunk)) {
            if (normalizedQuestion.contains(keyword)) {
                keywordHits++;
            }
        }

        int matched = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                matched++;
            }
        }
        double coverage = matched / (double) terms.size();

        double titleHit = 0;
        String normalizedTitle = normalize(chunk.title());
        for (String term : terms) {
            if (normalizedTitle.contains(term)) {
                titleHit = 2.0;
                break;
            }
        }
        return keywordHits * 4.0 + coverage * 10.0 + titleHit;
    }

    private List<String> chunkKeywords(AiKnowledgeChunk chunk) {
        List<String> result = new ArrayList<>();
        for (String part : chunk.keywords().split("[,，、]")) {
            String keyword = normalize(part);
            if (!keyword.isBlank()) {
                result.add(keyword);
            }
        }
        return result;
    }

    /**
     * 中文无空格分词：按连续汉字切段取二元词（「积分规则」→ 积分/分规/规则），
     * 英文按长度 ≥2 的单词切。够用且不需要引入分词器。
     */
    List<String> terms(String normalizedQuestion) {
        Set<String> result = new LinkedHashSet<>();
        Matcher han = HAN_RUN.matcher(normalizedQuestion);
        while (han.find()) {
            String run = han.group();
            if (run.length() == 1) {
                if (!STOP_TERMS.contains(run)) {
                    result.add(run);
                }
                continue;
            }
            for (int i = 0; i + 1 < run.length(); i++) {
                String bigram = run.substring(i, i + 2);
                if (!STOP_TERMS.contains(bigram)) {
                    result.add(bigram);
                }
            }
        }
        Matcher ascii = ASCII_WORD.matcher(normalizedQuestion);
        while (ascii.find()) {
            result.add(ascii.group());
        }
        return new ArrayList<>(result);
    }

    private String normalize(String text) {
        return NOISE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
}
