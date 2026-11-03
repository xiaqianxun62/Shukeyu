package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端赛事列表项（PC 官网管理台）
 *
 * <p>与「赛事广场」的 {@link TournamentListItem} 定位不同：
 * <ul>
 *   <li>广场视角面向球友：只展示没取消的、给报名入口用；</li>
 *   <li>管理视角面向组织者：<b>包含已取消的</b>、可按状态筛选、
 *       带出每组别报名明细数与创建人，并给出赛制配置，便于判断能不能抽签。</li>
 * </ul>
 */
@Data
@Builder
public class TournamentManageItem {

    private Long id;

    private String title;

    private String location;

    private LocalDate playDate;

    private String remark;

    /** 0 报名中 1 已抽签 2 进行中 3 已结束 4 已取消 */
    private Integer status;

    private String statusText;

    /** 开设组别 code 列表，如 [1, 2, 4] */
    private List<Integer> formats;

    /** 开设组别名列表，如 [单打, 男双, 混双] */
    private List<String> formatNames;

    /** 小组赛每组队数 */
    private Integer groupSize;

    /** 每组晋级名额 */
    private Integer advancePerGroup;

    /** 单组别参赛队数上限 */
    private Integer maxEntries;

    /** 小组赛轮数配置：0 = 自动（打满单循环） */
    private Integer groupRounds;

    /** 淘汰赛轮数配置：0 = 自动（按晋级人数推导） */
    private Integer knockoutRounds;

    /** 参赛单位总数（跨所有组别） */
    private int entryCount;

    /** 各组别报名明细数 */
    private List<FormatCount> formatCounts;

    /** 创建人 */
    private Long creatorId;

    private String creatorName;

    /** 是否已抽签（status != 报名中） */
    private boolean drawn;

    /** 冠军队名，未产生时为 null */
    private String championName;

    private LocalDateTime createTime;

    /** 单个组别的报名统计 */
    @Data
    @Builder
    public static class FormatCount {

        private int format;

        private String formatName;

        /** 该组别已报名队数 */
        private int entryCount;

        /** 该组别参赛队数上限 */
        private int maxEntries;

        /** 该组别已满 */
        private boolean full;

        /** 按当前配置推算的淘汰赛规模（组数 × 每组名额）；不合法时为 0 */
        private int knockoutSize;

        /** 淘汰赛规模是否合法（2 的幂且 ≥4），不合法则抽签会被后端拒绝 */
        private boolean knockoutSizeValid;

        /* ---------- 赛程结构：开赛前就能看清要打几轮 ---------- */

        /** 小组数 */
        private int groupCount;

        /** 每组实际队数（均摊后的结果；余数多时前面的组多 1 队） */
        private int teamSizePerGroup;

        /** 小组赛轮数：偶数队 k-1 轮、奇数队 k 轮（补虚拟队制造轮空）；指定轮数时按配置 */
        private int groupRounds;

        /** 小组赛场次合计 */
        private int groupMatches;

        /** 小组赛是否被指定了轮数（未指定时按单循环自动推算） */
        private boolean groupRoundsConfigured;

        /** 淘汰赛轮数：32强→16强→8强→四强 共 4 轮 */
        private int knockoutRounds;

        /** 淘汰赛场次合计（不含季军赛 / 决赛） */
        private int knockoutMatches;

        /** 收官战场次（季军赛 + 决赛），恒为 2 */
        private int finalMatches;

        /** 首轮轮空队数（签位多于晋级队时出现） */
        private int byeCount;

        /** 该组别总场次 */
        private int totalMatches;
    }
}
