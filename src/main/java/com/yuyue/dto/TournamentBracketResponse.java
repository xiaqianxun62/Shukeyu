package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 赛事对阵图：每个组别一张完整赛程（小组赛积分榜 + 各轮对阵 + 最终名次）
 *
 * <p>小组赛与淘汰赛分开返回：小组赛要展示组内积分榜（胜场 / 净胜分 / 排名），
 * 淘汰赛是逐轮收敛的对阵树，两者前端版式差异很大，混在一个列表里反而难渲染。
 */
@Data
@Builder
public class TournamentBracketResponse {

    private Long id;

    private String title;

    private Integer status;

    private String statusText;

    /** 各组别的完整赛程 */
    private List<FormatBoard> formats;

    /** 单个组别的赛程 */
    @Data
    @Builder
    public static class FormatBoard {

        private int format;

        private String formatName;

        /** 小组赛（未抽签时为空列表） */
        private List<GroupBoard> groups;

        /** 淘汰赛逐轮 + 季军赛 + 决赛 */
        private List<RoundBoard> knockouts;

        /** 最终名次榜（按名次升序，未淘汰的队伍不在其中） */
        private List<RankItem> ranking;

        /** 仍在赛、没有名次的队伍（一般是还没打到这一轮） */
        private List<TournamentMatchItem.TeamItem> alive;
    }

    /** 小组：组内积分榜 + 全部组内对阵 */
    @Data
    @Builder
    public static class GroupBoard {

        private Long groupId;

        private String groupNo;

        private int entryCount;

        /** 0 未开始 1 进行中 2 已完赛 */
        private Integer status;

        /** 组内积分榜，按名次升序 */
        private List<StandingItem> standings;

        /** 组内对阵，按轮次再按场次 */
        private List<TournamentMatchItem> matches;
    }

    /** 组内一条成绩 */
    @Data
    @Builder
    public static class StandingItem {

        private Long entryId;

        private String name;

        private List<TournamentMatchItem.MemberBrief> members;

        /** 组内名次，1 = 小组第一 */
        private int rank;

        private int win;

        private int loss;

        /** 净胜分：同胜场时的排序依据 */
        private int pointDiff;

        /** 1 已晋级淘汰赛 */
        private boolean advance;
    }

    /** 淘汰赛的一轮，或季军赛 / 决赛 */
    @Data
    @Builder
    public static class RoundBoard {

        /** 2 淘汰赛轮次序号；季军赛 / 决赛为 0 */
        private int roundNo;

        /** 本轮参赛队数：32 / 16 / 8 / 4；季军赛 / 决赛为 0 */
        private int roundSize;

        /** 轮次中文名：三十二强 / 十六强 / 八强 / 四强 / 季军赛 / 决赛 */
        private String roundName;

        /** 本轮是否已全部出结果 */
        private boolean finished;

        private List<TournamentMatchItem> matches;
    }

    /** 一条最终名次 */
    @Data
    @Builder
    public static class RankItem {

        private Integer rank;

        private Long entryId;

        private String name;

        private List<TournamentMatchItem.MemberBrief> members;

        private String groupNo;

        /** 止步说明，如「八强」「季军赛负」；名次前三为 null */
        private String eliminatedText;
    }
}