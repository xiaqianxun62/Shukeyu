package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 身份校验问题库：管理员在后台增删改，用户自愿答题通过身份校验。
 * answer 字段 @JsonIgnore，绝不能通过 API 返回给前端。
 */
@Data
@TableName("verification_question")
public class VerificationQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 问题文本 */
    private String question;

    /**
     * 正确答案；多个答案用 "|" 分隔。
     * 比对规则：trim + 忽略大小写。
     */
    @JsonIgnore
    private String answer;

    /** 1启用 0停用 */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
