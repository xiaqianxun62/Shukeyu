package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话消息
 * <p>
 * 不设逻辑删除：删会话时消息随会话一起不可见（查询都先校验会话归属），
 * 物理保留便于事后复盘，不做定期清理。
 */
@Data
@TableName("ai_message")
public class AiMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long userId;

    /** USER / ASSISTANT */
    private String role;

    private String content;

    /** 命中的知识库条目摘要，分号分隔；仅回答行有值 */
    private String citations;

    /** 本次回答用到的数据工具，逗号分隔；仅回答行有值 */
    private String tools;

    private Integer totalTokens;

    private Integer latencyMs;

    private LocalDateTime createTime;
}
