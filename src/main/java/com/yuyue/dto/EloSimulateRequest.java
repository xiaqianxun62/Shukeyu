package com.yuyue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

/**
 * ELO 试算请求：给定男女人数与积分，复用真实编排引擎 + ELO 计算器做一次模拟。
 * <p>
 * 只读计算，不落库、不影响真实积分，用于验证 ELO 机制。
 */
@Data
public class EloSimulateRequest {

    @Min(value = 0, message = "男人数不能为负")
    @Max(value = 40, message = "男人数最多 40 人")
    private int maleCount = 2;

    @Min(value = 0, message = "女人数不能为负")
    @Max(value = 40, message = "女人数最多 40 人")
    private int femaleCount = 2;

    /** 初始积分，按「先男后女」顺序；缺失或为 null 时用默认积分补齐 */
    private List<Integer> ratings;

    /**
     * 指定编排方案：1 全单打 2 全混双 3 全男双 4 全女双 5 混搭·混双优先 6 混搭·同性别优先
     * 7 混双·纯随机（一男一女组队但随机配） 8 全随机（不分性别）。
     * 为空时按性别构成自动过滤（与线上 /games/{id}/arrange 行为一致）。
     */
    private Integer schemeId;

    /**
     * 赛制（与 schemeId 二选一，同时传时以 schemeId 为准）：
     * 1 单打 → 全单打方案，2 男双 → 全男双，3 女双 → 全女双，4 混双 → 全混双。
     */
    @Min(value = 1, message = "请先选择赛制：单打、男双、女双或混双")
    @Max(value = 4, message = "请先选择赛制：单打、男双、女双或混双")
    private Integer format;

    /**
     * 赛制：false=单轮（每人只打一场），true=循环赛（每支队伍与其他所有队伍各打一场，共 k*(k-1)/2 局）。
     * 循环赛下积分逐场累计：下一场以上一场打完的分数为起点。
     */
    private Boolean roundRobin = false;

    /** 统一历史场次（决定 K 因子：≤20 场 K=40，21-60 K=24，>60 K=16） */
    @Min(value = 0, message = "历史场次不能为负")
    private int gamesPlayed = 0;

    /** 每场对阵的胜方：1=A队 2=B队；为空或长度不符时默认 A 队胜 */
    private List<Integer> winners;

    /**
     * 试算类别（前端选择 单打/双打/混双 后传）：1单打 2双打 3混双。
     * 未显式传 format/schemeId 时按类别预设赛制；前端通常还会传对应类别的积分作输入。
     */
    @Min(value = 1, message = "试算类别无效")
    @Max(value = 3, message = "试算类别无效")
    private Integer category;
}
