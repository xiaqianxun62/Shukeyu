package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ELO 积分变动流水
 */
@Data
@TableName("rating_history")
public class RatingHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long matchId;

    /** ELO 类别：1单打 2双打 3混双（V30 起有值，旧流水按 match format 回填） */
    private Integer category;

    private Integer ratingBefore;

    private Integer ratingAfter;

    private Integer delta;

    private Integer kFactor;

    /** 期望胜率 E（已含性别修正） */
    private BigDecimal expected;

    /**
     * 局内分差加成部分：胜方正、负方负，与 delta 反向对称。
     * 净胜分在触发线以内为 0（此时 delta 完全来自 K×(S−E)）。
     */
    private Integer marginDelta;

    /**
     * 本场该队所属队伍的性别修正 handicap（分）：
     * 正值表示因性别构成吃亏（期望被压低），负值表示占便宜，0 表示同赛制或中性。
     */
    private BigDecimal genderAdjust;

    private LocalDateTime createTime;
}
