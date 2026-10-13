package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计分日志：每一次比分提交都留痕。
 * 首次提交比分的用户即本场裁判（action=1）；之后每次改分记录改前/改后比分（action=2）。
 */
@Data
@TableName("match_score_log")
public class MatchScoreLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对阵 match_game.id */
    private Long matchId;

    /** 所属球局 */
    private Long gameId;

    /** 提交比分的用户（裁判 / 修改人） */
    private Long operatorId;

    /** 1 首次计分（裁判） 2 修改比分 */
    private Integer action;

    /** 本次提交的 A 队比分 */
    private Integer scoreA;

    /** 本次提交的 B 队比分 */
    private Integer scoreB;

    /** 改前 A 队比分（首次计分为 null） */
    private Integer prevScoreA;

    /** 改前 B 队比分（首次计分为 null） */
    private Integer prevScoreB;

    private LocalDateTime createTime;
}
