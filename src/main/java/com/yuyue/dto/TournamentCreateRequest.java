package com.yuyue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 创建赛事（管理员）
 *
 * <p>小组规模与晋级名额都是「按赛事实配」而不是写死：
 * 淘汰赛规模 = 组数 × 每组名额，必须凑成 2 的幂（4/8/16/32/64/128）才打得了完整签表，
 * 所以规模的合法性由TournamentService 结合实际报名人数在<b>抽签时</b>校验，
 * 创建时只做基础区间校验。
 */
@Data
public class TournamentCreateRequest {

    @NotBlank(message = "赛事名称不能为空")
    @Size(max = 64, message = "赛事名称不能超过 64 字")
    private String title;

    private String location;

    /** 关联球场字典（court 表，可空） */
    private Long courtId;

    private LocalDate playDate;

    /** 赛事说明 / 补充规则 */
    @Size(max = 500, message = "赛事说明不能超过 500 字")
    private String remark;

    /**
     * 开设组别：1单打 2男双 3女双 4混双
     * 不传默认四个组别全开
     */
    private List<Integer> formats;

    /** 小组赛每组队数（组内单循环，至少 2，默认 4） */
    @Min(value = 2, message = "每组至少 2 队")
    @Max(value = 16, message = "每组最多 16 队")
    private Integer groupSize = 4;

    /** 每组晋级名额（至少 1，默认 1） */
    @Min(value = 1, message = "每组晋级名额至少 1")
    @Max(value = 8, message = "每组晋级名额最多 8")
    private Integer advancePerGroup = 1;

    /** 单个组别的参赛队数上限（双打按队计，默认 64） */
    @Min(value = 4, message = "参赛上限至少 4 队")
    @Max(value = 512, message = "参赛上限最多 512 队")
    private Integer maxEntries = 64;

    /**
     * 小组赛轮数：0 = 自动（按每组队数打满单循环）；&gt; 0 = 指定轮数。
     * <p>小于满轮数即部分循环（每队交手次数变少，排名依据变弱）；
     * 大于满轮数即多循环（双循环及以上，相邻两遍主客互换）。
     */
    @Min(value = 0, message = "小组赛轮数不能为负")
    @Max(value = 45, message = "小组赛轮数最多 45 轮")
    private Integer groupRounds = 0;

    /**
     * 淘汰赛轮数（不含季军赛 / 决赛）：0 = 自动（按晋级人数推导）。
     * <p>&gt; 0 时规模 = 2^(轮数+1)；晋级人数超出该规模时自动扩容到能容纳的规模，
     * 容纳不下时首轮出现轮空。
     */
    @Min(value = 0, message = "淘汰赛轮数不能为负")
    @Max(value = 7, message = "淘汰赛轮数最多 7 轮（256 强）")
    private Integer knockoutRounds = 0;
}