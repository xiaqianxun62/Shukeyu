package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 比赛对阵：小组赛组内单循环 + 逐轮淘汰赛 + 季军赛 / 决赛，统一一张表。
 *
 * <p>淘汰赛不做「预生成整棵对阵树」，而是本轮全部出结果后用胜者随机配对生成下一轮，
 * 因此不依赖固定规模，改赛事配置也不用改代码。
 */
@Data
@TableName("tournament_match")
public class TournamentMatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属赛事 */
    private Long tournamentId;

    /** 1单打 2男双 3女双 4混双 */
    private Integer format;

    /** 小组赛所属小组；淘汰赛为空 */
    private Long groupId;

    /** 1 小组赛 2 淘汰赛 3 季军赛 4 决赛 */
    private Integer stage;

    /** 小组赛=组内轮次；淘汰赛=第几轮（从 1 起） */
    private Integer roundNo;

    /** 淘汰赛本轮参赛队数（32/16/8/4，前端据此显示轮次名）；小组赛=组内队数 */
    private Integer roundSize;

    /** 同轮次内的场次序号（从 1 起） */
    private Integer matchNo;

    /** 场地号，从 1 开始 */
    private Integer court;

    /** A 队参赛单位 */
    private Long teamA;

    /** B 队参赛单位 */
    private Long teamB;

    /** 0 未出结果 1 A队胜 2 B队胜 */
    private Integer winner;

    /** 可选比分 A（21 分制，仅作记录，胜方由winner 决定） */
    private Integer scoreA;

    /** 可选比分 B */
    private Integer scoreB;

    /** 计分人（首次提交比分的用户） */
    private Long refereeId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}