package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 参赛单位明细（管理端报名名单）
 *
 * <p>管理端要「移出参赛者」必须知道参赛单位 id、队名与当前战绩，
 * 否则前端无从下手；这也是赛事广场视图刻意不暴露的字段。
 */
@Data
@Builder
public class TournamentEntryItem {

    /** 参赛单位 id（tournament_entry.id），移出时用这个 */
    private Long entryId;

    private Long tournamentId;

    /** 1 单打 2 男双 3 女双 4 混双 */
    private Integer format;

    private String formatName;

    /** 队名：双人「张三 / 李四」，单人即姓名 */
    private String name;

    /** 队员名片（单打 1 人，双打 2 人） */
    private List<TournamentMatchItem.MemberBrief> members;

    /** 联系电话（报名时填写的，可选） */
    private String contact;

    /** 抽签号，未抽签为 null */
    private Integer seedNo;

    /** 小组名，未抽签为 null */
    private String groupNo;

    /** 组内名次，未定出为 null */
    private Integer groupRank;

    private int groupWin;

    private int groupLoss;

    /** 小组赛净胜分 */
    private int groupPointDiff;

    /** 1 已晋级淘汰赛 */
    private boolean advance;

    /** 0 已报名 1 已抽签 2 仍在赛 3 已淘汰 */
    private Integer status;

    private String statusText;

    /** 止步阶段，未淘汰为 null */
    private Integer eliminatedStage;

    /** 最终名次，未定为 null */
    private Integer finalRank;

    private LocalDateTime createTime;
}
