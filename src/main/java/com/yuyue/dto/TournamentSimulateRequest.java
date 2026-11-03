package com.yuyue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

/**
 * 模拟赛程请求（管理员）
 *
 * <p>目的是「开赛前先看清楚这场赛到底要打几轮、长什么样」：
 * <ul>
 *   <li>传 {@code tournamentId}：沿用该赛事的组别与赛制配置，队伍数取<b>真实报名数</b>；</li>
 *   <li>不传 {@code tournamentId} 或显式传 {@code entryCount}：
 *       用纯参数预演——「假如有 16 队、每组 4 人、每组 2 名晋级，赛程长什么样」。</li>
 * </ul>
 *
 * <p><b>全程不落库</b>：分组、赛果、名次全部在内存里算完就丢，
 * 不会污染 tournament / tournament_entry / tournament_group / tournament_match 四张表。
 * 每次模拟的结果都随机（抽签随机 + 赛果随机），所以它只能用来预览结构，不能当赛程依据。
 */
@Data
public class TournamentSimulateRequest {

    /** 基于哪个赛事预演；为空则纯参数预演 */
    private Long tournamentId;

    /** 开设组别：1 单打 2 男双 3 女双 4 混双；不传则用赛事配置，全空则四组别全开 */
    private List<Integer> formats;

    /** 小组赛每组队数（2~16） */
    @Min(value = 2, message = "每组至少 2 队")
    @Max(value = 16, message = "每组最多 16 队")
    private Integer groupSize;

    /** 每组晋级名额（1~8） */
    @Min(value = 1, message = "每组晋级名额至少 1")
    @Max(value = 8, message = "每组晋级名额最多 8")
    private Integer advancePerGroup;

    /** 模拟的队伍数（不传则取真实报名数；上限 512 队） */
    @Min(value = 0, message = "模拟队伍数不能为负")
    @Max(value = 512, message = "模拟队伍数最多 512 队")
    private Integer entryCount;

    /** 小组赛轮数：0 = 自动（打满单循环）；&gt; 0 = 指定轮数（可多循环） */
    @Min(value = 0, message = "小组赛轮数不能为负")
    @Max(value = 45, message = "小组赛轮数最多 45 轮")
    private Integer groupRounds;

    /** 淘汰赛轮数（不含季军赛 / 决赛）：0 = 自动（按晋级人数推导） */
    @Min(value = 0, message = "淘汰赛轮数不能为负")
    @Max(value = 7, message = "淘汰赛轮数最多 7 轮（256 强）")
    private Integer knockoutRounds;
}
