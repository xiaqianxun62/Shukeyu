package com.yuyue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 修改赛事（管理员）
 *
 * <p>可改字段的边界由赛制阶段决定，Service 会强制校验：
 * <ul>
 *   <li><b>报名中</b>：全部字段可改（含组别与赛制配置）；</li>
 *   <li><b>已抽签</b>：分组与赛程已生成并落库，组别 / 每组队数 / 晋级名额 / 上限一律锁定，
 *       只允许改标题、地点、日期、球场、说明；</li>
 *   <li><b>进行中 / 已结束 / 已取消</b>：全部锁定，不接受修改。</li>
 * </ul>
 */
@Data
public class TournamentUpdateRequest {

    @NotBlank(message = "赛事名称不能为空")
    @Size(max = 64, message = "赛事名称不能超过 64 字")
    private String title;

    private String location;

    /** 关联球场字典（court 表，可空） */
    private Long courtId;

    private LocalDate playDate;

    @Size(max = 500, message = "赛事说明不能超过 500 字")
    private String remark;

    /**
     * 开设组别：1 单打 2 男双 3 女双 4 混双；不传表示不改（已抽签时忽略）。
     */
    private List<Integer> formats;

    /** 小组赛每组队数（组内单循环，至少 2，默认 4） */
    @Min(value = 2, message = "每组至少 2 队")
    @Max(value = 16, message = "每组最多 16 队")
    private Integer groupSize;

    /** 每组晋级名额（至少 1，默认 1） */
    @Min(value = 1, message = "每组晋级名额至少 1")
    @Max(value = 8, message = "每组晋级名额最多 8")
    private Integer advancePerGroup;

    /** 单个组别的参赛队数上限（双打按队计，默认 64） */
    @Min(value = 4, message = "参赛上限至少 4 队")
    @Max(value = 512, message = "参赛上限最多 512 队")
    private Integer maxEntries;

    /** 小组赛轮数：0 = 自动（打满单循环）；&gt; 0 = 指定轮数（可多循环） */
    @Min(value = 0, message = "小组赛轮数不能为负")
    @Max(value = 45, message = "小组赛轮数最多 45 轮")
    private Integer groupRounds;

    /** 淘汰赛轮数（不含季军赛 / 决赛）：0 = 自动（按晋级人数推导） */
    @Min(value = 0, message = "淘汰赛轮数不能为负")
    @Max(value = 7, message = "淘汰赛轮数最多 7 轮（256 强）")
    private Integer knockoutRounds;
}
