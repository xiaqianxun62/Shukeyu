package com.yuyue.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuyue.ai.dto.AiConversationDetail;
import com.yuyue.ai.dto.AiConversationSummary;
import com.yuyue.ai.dto.AiMessageItem;
import com.yuyue.common.ErrorCode;
import com.yuyue.entity.AiConversation;
import com.yuyue.entity.AiMessage;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.AiConversationMapper;
import com.yuyue.mapper.AiMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话与消息的读写。
 * <p>
 * 安全边界在这里：所有对外方法都要求 userId，并且查询条件里带 user_id。
 * 只按会话 ID 查是经典的越权洞——猜一个自增 ID 就能读别人和 AI 的对话。
 * <p>
 * 计数写在会话行上（message_count / last_message_at），列表页就不用对消息表做 count(*)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConversationService {

    /** 列表最多返回最近 30 条：会话多了就是「话题归档」，翻旧账用不到 */
    private static final int LIST_LIMIT = 30;
    /** 单次详情最多返回最近 100 条，防止一个超长会话把响应撑爆 */
    private static final int MESSAGE_LIMIT = 100;
    private static final int TITLE_MAX = 30;

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;

    public AiConversation create(Long userId, String firstQuestion) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setTitle(buildTitle(firstQuestion));
        conversation.setMessageCount(0);
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationMapper.insert(conversation);
        return conversation;
    }

    /** 取会话并校验归属；不存在或不属于当前用户一律 404，不暴露「存在但不是你的」 */
    public AiConversation requireOwned(Long userId, Long conversationId) {
        AiConversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getId, conversationId)
                        .eq(AiConversation::getUserId, userId));
        if (conversation == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    public List<AiConversationSummary> list(Long userId) {
        List<AiConversation> rows = conversationMapper.selectList(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getUserId, userId)
                        .orderByDesc(AiConversation::getLastMessageAt)
                        .orderByDesc(AiConversation::getId)
                        .last("LIMIT " + LIST_LIMIT));
        List<AiConversationSummary> result = new ArrayList<>(rows.size());
        for (AiConversation row : rows) {
            result.add(toSummary(row));
        }
        return result;
    }

    public AiConversationDetail detail(Long userId, Long conversationId) {
        AiConversation conversation = requireOwned(userId, conversationId);
        List<AiMessage> rows = messageMapper.selectList(
                new LambdaQueryWrapper<AiMessage>()
                        .eq(AiMessage::getConversationId, conversationId)
                        .orderByDesc(AiMessage::getId)
                        .last("LIMIT " + MESSAGE_LIMIT));
        // 查询按倒序取最近 N 条，返回给前端按时间正序
        Collections.reverse(rows);
        List<AiMessageItem> messages = new ArrayList<>(rows.size());
        for (AiMessage row : rows) {
            messages.add(new AiMessageItem(row.getId(), row.getRole(), row.getContent(),
                    splitCitations(row.getCitations()), splitTools(row.getTools()),
                    row.getTotalTokens(), row.getLatencyMs(), row.getCreateTime()));
        }
        return new AiConversationDetail(toSummary(conversation), messages);
    }

    /**
     * 取最近若干条历史（不含本轮提问），按时间正序返回给提示词。
     */
    public List<AiMessage> history(Long conversationId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<AiMessage> rows = messageMapper.selectList(
                new LambdaQueryWrapper<AiMessage>()
                        .eq(AiMessage::getConversationId, conversationId)
                        .orderByDesc(AiMessage::getId)
                        .last("LIMIT " + limit));
        Collections.reverse(rows);
        return rows;
    }

    public void appendUser(Long conversationId, Long userId, String question) {
        append(conversationId, userId, AiMessage.ROLE_USER, question, null, null, 0, 0);
    }

    public AiMessage appendAssistant(Long conversationId, Long userId, String content,
                                     String citations, String tools, int totalTokens, int latencyMs) {
        return append(conversationId, userId, AiMessage.ROLE_ASSISTANT, content,
                citations, tools, totalTokens, latencyMs);
    }

    public void delete(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);
        // @TableLogic：这里是软删除，消息保留（查询都先校验会话，已删会话读不到消息）
        conversationMapper.deleteById(conversationId);
    }

    private AiMessage append(Long conversationId, Long userId, String role, String content,
                             String citations, String tools, int totalTokens, int latencyMs) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setCitations(citations);
        message.setTools(tools);
        message.setTotalTokens(totalTokens);
        message.setLatencyMs(latencyMs);
        message.setCreateTime(LocalDateTime.now());
        messageMapper.insert(message);

        // 用 SQL 自增而不是读-改-写：同一会话并发两问也不会丢计数
        conversationMapper.update(null, new LambdaUpdateWrapper<AiConversation>()
                .eq(AiConversation::getId, conversationId)
                .setSql("message_count = message_count + 1")
                .set(AiConversation::getLastMessageAt, message.getCreateTime()));
        return message;
    }

    private AiConversationSummary toSummary(AiConversation row) {
        return new AiConversationSummary(row.getId(), row.getTitle(), row.getMessageCount(),
                row.getLastMessageAt(), row.getCreateTime());
    }

    private String buildTitle(String firstQuestion) {
        String oneLine = firstQuestion == null ? "" : firstQuestion.replaceAll("\\s+", " ").strip();
        if (oneLine.isEmpty()) {
            return "新的对话";
        }
        return oneLine.length() <= TITLE_MAX ? oneLine : oneLine.substring(0, TITLE_MAX) + "…";
    }

    private List<String> splitCitations(String raw) {
        return splitBy(raw, ";");
    }

    private List<String> splitTools(String raw) {
        return splitBy(raw, ",");
    }

    private List<String> splitBy(String raw, String separator) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(java.util.regex.Pattern.quote(separator))) {
            if (!part.isBlank()) {
                result.add(part.strip());
            }
        }
        return result;
    }
}
