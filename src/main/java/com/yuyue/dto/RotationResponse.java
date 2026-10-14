package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 轮排结果：逐轮给出每片场地的对阵与本轮休息人员，并附带每人的上场 / 休息统计
 */
@Data
@Builder
public class RotationResponse {

    private int courts;

    private int rounds;

    private int format;

    private String formatName;

    /** 参与人员（含展示名与积分），前端据此把 userId 渲染成昵称 */
    private List<PlayerBrief> players;

    private List<RoundItem> rotation;

    private List<StatItem> stats;

    /** 报名统计：总人数、男生、女生 */
    private int totalPlayers;
    private int maleCount;
    private int femaleCount;

    @Data
    @Builder
    public static class PlayerBrief {
        private Long userId;
        private String label;
        private int gender;
        private int rating;
        /** 个人头像 URL */
        private String avatar;
    }

    @Data
    @Builder
    public static class RoundItem {
        private int round;
        private List<MatchItem> matches;
        /** 本轮轮空 / 休息的人 */
        private List<PlayerBrief> resting;
    }

    @Data
    @Builder
    public static class MatchItem {
        /** 场地号，从 1 开始 */
        private int court;
        private int format;
        /** 单打 / 男双 / 女双 / 混双（整场口径：两队构成不一致时按 A 队定） */
        private String formatName;
        /** A 队实际构成：单打 / 男双 / 女双 / 混双（性别信息不全时为 null） */
        private String teamAFormatName;
        /** B 队实际构成：单打 / 男双 / 女双 / 混双（性别信息不全时为 null） */
        private String teamBFormatName;
        private List<PlayerBrief> teamA;
        private List<PlayerBrief> teamB;
        /** 两队积分和，用于判断本场是否势均力敌 */
        private int sumA;
        private int sumB;
        /** A 队的 ELO 期望胜率（0-1），越接近 0.5 越势均力敌 */
        private Double expectedA;
        /**
         * 真实球局生成轮排时，每片场地会对应 match_game 表里的一条对阵；
         * 快速试算（没有 gameId）时为 null。
         */
        private Long matchId;
        private Integer scoreA;
        private Integer scoreB;
        /** 1 A队胜 2 B队胜 0 未计分 */
        private Integer winner;

        /** 裁判：首次提交比分的用户；未计分时为 null */
        private Long refereeId;

        /** 裁判展示名（优先昵称，回退姓名） */
        private String refereeName;

        /** 计分留痕：谁当裁判、计了多少分、谁后面又改了（按时间升序） */
        private List<ScoreLogItem> scoreLogs;
    }

    /** 单次计分操作记录 */
    @Data
    @Builder
    public static class ScoreLogItem {

        private Long operatorId;

        /** 操作人展示名（优先昵称，回退姓名） */
        private String operatorName;

        /** 操作人头像 URL（未上传时为 null，前端用首字兜底） */
        private String operatorAvatar;

        /** 1 首次计分（裁判） 2 修改比分 */
        private Integer action;

        private Integer scoreA;

        private Integer scoreB;

        /** 改前比分（首次计分为 null） */
        private Integer prevScoreA;

        private Integer prevScoreB;

        private LocalDateTime time;
    }

    @Data
    @Builder
    public static class StatItem {
        private Long userId;
        private String label;
        private int gender;
        private int rating;
        /** 个人头像 URL */
        private String avatar;
        /** 上场轮数 */
        private int playCount;
        /** 休息轮数 */
        private int restCount;
        /** 胜场数（已计分的对阵） */
        private int winCount;
        /** 败场数（已计分的对阵） */
        private int loseCount;
        /** 净胜分（所在队伍每局得分-失分累加） */
        private int points;
        /** 本球局累计 ELO 变化（胜为正、负为负），来自 rating_history */
        private int eloDelta;
        /** 与谁搭档过、各几次 */
        private List<PartnerItem> partners;
    }

    @Data
    @Builder
    public static class PartnerItem {
        private Long userId;
        private String label;
        private int times;
    }
}
