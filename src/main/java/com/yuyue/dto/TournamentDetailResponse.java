package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 赛事详情
 *
 * <p>承担三件事：基本信息、每个组别的报名情况（含我报没报）、以及已产生的领奖台。
 */
@Data
@Builder
public class TournamentDetailResponse {

    private Long id;

    private String title;

    private String location;

    /** 球场名称（关联 court 表解析出来的，赛事自己填了 location 时以location 为准） */
    private String locationName;

    private LocalDate playDate;

    private String remark;

    private Integer status;

    private String statusText;

    /** 小组赛每组队数 */
    private Integer groupSize;

    /** 每组晋级名额 */
    private Integer advancePerGroup;

    /** 单组别参赛队数上限 */
    private Integer maxEntries;

    /** 是否已抽签分组 */
    private boolean drawn;

    /** 当前登录用户是否管理员：决定前端是否显示「抽签 / 编排 / 取消赛事」等管理入口 */
    private boolean admin;

    /** 报名是否开放（status=0 且未抽签） */
    private boolean openForEntry;

    /** 开设组别的报名情况 */
    private List<FormatStat> formats;

    /** 我报名的参赛单位（可能报多个组别，所以是列表） */
    private List<MyEntryItem> myEntries;

    /** 各组别的领奖台（未出冠军时该组别不出现在列表里） */
    private List<PodiumItem> podium;

    /** 单个组别的报名情况 */
    @Data
    @Builder
    public static class FormatStat {

        /** 1 单打 2 男双 3 女双 4 混双 */
        private int format;

        private String formatName;

        /** 每队人数：单打 1、双打 2 */
        private int teamSize;

        /** 已报名队数 */
        private int entryCount;

        /** 队数上限 */
        private int maxEntries;

        /** 人数上限 */
        private int maxPeople;

        /** 名额已满 */
        private boolean full;

        /** 我是否已报这个组别 */
        private boolean joined;

        /** 抽签后实际生成的组数（未抽签为 0） */
        private int groupCount;
    }

    /** 我报名的参赛单位 */
    @Data
    @Builder
    public static class MyEntryItem {

        private Long entryId;

        private int format;

        private String formatName;

        private String name;

        private String groupNo;

        /** 0 已报名 1 已抽签 2 仍在赛 3 已淘汰 */
        private Integer status;

        private String statusText;

        /** 最终名次，未定为 null */
        private Integer finalRank;

        /** 止步说明，如「止步八强」 */
        private String eliminatedText;
    }

    /** 领奖台条目 */
    @Data
    @Builder
    public static class PodiumItem {

        /** 1 冠军 2 亚军 3 季军 4 第 4 名 */
        private int rank;

        private String rankName;

        private int format;

        private String formatName;

        private String name;

        private List<TournamentMatchItem.MemberBrief> members;
    }
}