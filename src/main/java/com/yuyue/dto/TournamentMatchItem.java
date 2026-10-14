package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 赛事对阵（小组赛 / 淘汰赛 / 季军赛 / 决赛共用）
 *
 * <p>对阵图与赛程录入两个页面共用这一份结构，前端可直接把 stage + roundSize
 * 渲染成「A 组第 2 轮」「十六强」「季军赛」等标签。
 */
@Data
@Builder
public class TournamentMatchItem {

    private Long id;

    /** 1 小组赛 2 淘汰赛 3 季军赛 4 决赛 */
    private Integer stage;

    /** 小组赛=组内轮次；淘汰赛=第几轮 */
    private Integer roundNo;

    /** 淘汰赛本轮参赛队数（32/16/8/4）；小组赛=组内队数 */
    private Integer roundSize;

    /** 轮次中文名：组内第 N 轮 / 三十二强 / 十六强 / 八强 / 四强 / 季军赛 / 决赛 */
    private String roundName;

    /** 组名，小组赛才有 */
    private String groupNo;

    /** 同轮次内的场次序号 */
    private Integer matchNo;

    /** 场地号，从 1 开始 */
    private Integer court;

    private TeamItem teamA;

    private TeamItem teamB;

    /** 0 未出结果 1 A队胜 2 B队胜 */
    private Integer winner;

    private Integer scoreA;

    private Integer scoreB;

    /** 计分人（首次提交比分的用户） */
    private Long refereeId;

    private String refereeName;

    /** 本场是否已有结果 */
    private boolean finished;

    /** 当前登录用户是否在这支队伍里（决定赛程录入页是否给「点胜方」的高亮提示） */
    private boolean involved;

    /** 一支参赛队伍 */
    @Data
    @Builder
    public static class TeamItem {

        /** 参赛单位 id（tournament_entry.id） */
        private Long entryId;

        /** 队名，如「张三 / 李四」 */
        private String name;

        /** 队员明细（单打 1 人，双打 2 人） */
        private List<MemberBrief> members;

        /** 组名（小组赛阶段展示） */
        private String groupNo;

        /** 1 已晋级淘汰赛 */
        private Boolean advance;

        /** 最终名次，未定为 null */
        private Integer finalRank;
    }

    /** 队员名片 */
    @Data
    @Builder
    public static class MemberBrief {

        private Long userId;

        /** 展示名（优先昵称，回退姓名） */
        private String name;

        private String avatar;

        /** 0 未知 1 男 2 女 */
        private Integer gender;
    }
}