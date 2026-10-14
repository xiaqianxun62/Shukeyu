package com.yuyue.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 轮排请求
 */
@Data
public class RotationRequest {

    /** 参与轮排的人；userId 可为空（试算时会用序号占位） */
    @Valid
    @NotEmpty(message = "还没有人参与轮排，请先添加球友")
    @Size(max = 40, message = "人数太多啦，一次最多排 40 人")
    private List<RotationPlayer> players;

    /** 场地数：每轮同时开打的场数 */
    @Min(value = 1, message = "至少要有 1 片场地")
    @Max(value = 20, message = "一次最多开 20 片场地")
    private int courts = 2;

    /** 轮数 */
    @Min(value = 1, message = "至少要排 1 轮")
    @Max(value = 30, message = "一次最多排 30 轮")
    private int rounds = 6;

    /** 1 单打（2 人/场） 2 双打（4 人/场），默认双打 */
    @Min(value = 1, message = "请先选择赛制：单打或双打")
    @Max(value = 2, message = "请先选择赛制：单打或双打")
    private int format = 2;

    /** 是否按 ELO 期望胜率均衡组队（每场双方期望胜率尽量接近 50%） */
    private Boolean balanceStrength = true;

    /**
     * 双打时本轮允许开哪几种场地（<b>可多选</b>，可任意组合）：
     * {@code mixed} 混双场（2 男 2 女）、{@code men} 男双场（4 男）、
     * {@code women} 女双场（4 女）、{@code any} 不限性别场（4 人性别任意）。
     *
     * <p>例：{@code ["men","mixed"]} = 男双场打男双 + 混双场打混双。
     * 只选 {@code ["men"]} 就不会出现任何女双 / 混双场地。
     * 单打时该字段忽略。
     */
    @Size(max = 4, message = "场地类型最多选 4 种，请取消多余的选择")
    private List<String> courtTypes;

    /**
     * 旧的单选性别规则（0~5）。<b>已废弃</b>，仅为兼容老客户端保留：
     * {@code courtTypes} 非空时以它为准，否则按本字段换算成等价的场地类型组合。
     *
     * <p>这是用户界面上的东西，正常操作永远不该触发它的范围校验。
     * 万一命中（老客户端数据异常），也不要把「性别规则」这种用户不认识的内部名词抛给用户。
     */
    @Min(value = 0, message = "轮排设置有误，请返回上一步重新选择后再试")
    @Max(value = 5, message = "轮排设置有误，请返回上一步重新选择后再试")
    private Integer genderRule = 0;

    @Data
    public static class RotationPlayer {

        /** 真实球局里传报名用户 id；试算时可不传 */
        private Long userId;

        /** 展示名，未填时自动生成 */
        private String label;

        /** 1男 2女 0未知 */
        private Integer gender;

        /** 积分，未填时按 1200 处理 */
        private Integer rating;
    }
}
