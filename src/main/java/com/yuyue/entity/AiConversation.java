package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话（一个用户可以有多条，互不干扰）
 */
@Data
@TableName("ai_conversation")
public class AiConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户：所有读写都必须带这个条件，否则等于越权 */
    private Long userId;

    /** 标题：取首条提问的前若干字，之后不再改 */
    private String title;

    /** 消息条数（含提问与回答），列表页直接展示，不用 count(*) */
    private Integer messageCount;

    /** 最后一条消息时间：会话列表按它倒序 */
    private LocalDateTime lastMessageAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
