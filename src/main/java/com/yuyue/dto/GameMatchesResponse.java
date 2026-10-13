package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 球局对阵（编排结果）。
 * 编排后落库，所有人（含未报名者）都能看到；每场比分由现场球友录入。
 */
@Data
@Builder
public class GameMatchesResponse {

    private Long gameId;

    private Integer status;

    private Integer schemeId;

    private String schemeName;

    /** 已录入比分的场次数 */
    private Integer finishedCount;

    private Integer totalCount;

    private List<MatchItem> matches;

    @Data
    @Builder
    public static class MatchItem {

        private Long matchId;

        /** 1单打 2男双 3女双 4混双 */
        private Integer format;

        private List<Long> teamA;

        private List<Long> teamB;

        /** 展示名：实名报名且查看者已登录时为真实姓名，否则为匿名昵称 */
        private List<String> teamANames;

        private List<String> teamBNames;

        private Integer scoreA;

        private Integer scoreB;

        /** 0 未定 1 A队 2 B队 */
        private Integer winner;

        /** 是否已结算积分 */
        private Boolean settled;

        /** 裁判：首次提交比分的用户；未计分时为 null */
        private Long refereeId;

        /** 裁判展示名（优先昵称，回退姓名） */
        private String refereeName;

        /** 计分历史：谁当裁判、计了多少分、谁后面又改了（按时间升序） */
        private List<ScoreLogItem> scoreLogs;
    }

    /** 单次计分操作记录 */
    @Data
    @Builder
    public static class ScoreLogItem {

        private Long operatorId;

        /** 操作人展示名（优先昵称，回退姓名） */
        private String operatorName;

        /** 1 首次计分（裁判） 2 修改比分 */
        private Integer action;

        private Integer scoreA;

        private Integer scoreB;

        /** 改前比分（首次计分为 null） */
        private Integer prevScoreA;

        private Integer prevScoreB;

        private LocalDateTime time;
    }
}
