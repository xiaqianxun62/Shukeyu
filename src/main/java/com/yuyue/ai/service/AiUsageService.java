package com.yuyue.ai.service;

import com.yuyue.ai.dto.AiChatResult;
import com.yuyue.ai.gateway.AiScene;
import com.yuyue.entity.AiUsageLog;
import com.yuyue.mapper.AiUsageLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * AI 用量落库。
 * <p>
 * 刻意「失败不影响主流程」：计量表写不进去（库抖动、字段变更）时只打 WARN，
 * 绝不能让用户的一次正常提问因为统计失败而报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageService {

    /** error_type 列宽 64，超长要截断，否则整行插入失败 */
    private static final int ERROR_TYPE_MAX = 64;

    private final AiUsageLogMapper usageLogMapper;

    public void recordSuccess(Long userId, AiScene scene, String provider, AiChatResult result) {
        AiUsageLog row = baseRow(userId, scene, provider, result.latencyMs());
        row.setModel(result.model());
        row.setPromptTokens(result.promptTokens());
        row.setCompletionTokens(result.completionTokens());
        row.setTotalTokens(result.totalTokens());
        row.setSuccess(1);
        insert(row);
    }

    public void recordFailure(Long userId, AiScene scene, String provider, String errorType, long latencyMs) {
        AiUsageLog row = baseRow(userId, scene, provider, latencyMs);
        row.setPromptTokens(0);
        row.setCompletionTokens(0);
        row.setTotalTokens(0);
        row.setSuccess(0);
        row.setErrorType(truncate(errorType));
        insert(row);
    }

    private AiUsageLog baseRow(Long userId, AiScene scene, String provider, long latencyMs) {
        AiUsageLog row = new AiUsageLog();
        row.setUserId(userId);
        row.setScene(scene == null ? null : scene.name());
        row.setProvider(provider);
        row.setLatencyMs((int) Math.min(Math.max(latencyMs, 0), Integer.MAX_VALUE));
        row.setCreateTime(LocalDateTime.now());
        return row;
    }

    private void insert(AiUsageLog row) {
        try {
            usageLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("AI 用量写入失败（不影响业务）: scene={} success={} error={}",
                    row.getScene(), row.getSuccess(), e.getMessage());
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_TYPE_MAX ? value : value.substring(0, ERROR_TYPE_MAX);
    }
}
