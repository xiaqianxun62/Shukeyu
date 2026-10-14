package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 比赛参赛单位（一行 = 一个参赛队）。
 *
 * <p>单打：只有 {@link #userId}，{@link #partnerId} 为空。
 * 双打：{@link #userId} 是队长（提交报名的人），{@link #partnerId} 是搭档。
 *
 * <p>小组赛战绩与最终名次直接挂在参赛单位上，不再单独建「小组成员」表。
 */
@Data
@TableName("tournament_entry")
public class TournamentEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属赛事 */
    private Long tournamentId;

    /** 1单打 2男双 3女双 4混双 */
    private Integer format;

    /** 队长（提交报名的人） */
    private Long userId;

    /** 双打搭档；单打为空 */
    private Long partnerId;

    /** 联系电话（可选） */
    private String contact;

    /** 抽签号（抽签后回填，1..N） */
    private Integer seedNo;

    /** 抽签后所在小组 */
    private Long groupId;

    /** 组名 A / B / C... */
    private String groupNo;

    /** 小组赛胜场 */
    private Integer groupWin;

    /** 小组赛负场 */
    private Integer groupLoss;

    /** 小组赛净胜分：胜方加分、负方减分，用于同胜场时的组内排序 */
    private Integer groupPointDiff;

    /** 小组名次（1 = 小组第一） */
    private Integer groupRank;

    /** 1 已晋级淘汰赛 0 未晋级 */
    private Integer advance;

    /** 0已报名 1已抽签 2仍在赛 3已淘汰 */
    private Integer status;

    /** 止步阶段：1小组赛 2淘汰赛 3季军赛 4决赛 */
    private Integer eliminatedStage;

    /** 最终名次：1冠军 2亚军 3季军 4第4，其余为并列区间起点 */
    private Integer finalRank;

    /** 生成列（数据库维护）：有效记录恒为 1，用于软删除兼容的唯一约束，实体侧只读忽略 */
    @TableField(exist = false)
    private Long activeMarker;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}