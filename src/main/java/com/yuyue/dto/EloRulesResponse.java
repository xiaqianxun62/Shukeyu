package com.yuyue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * ELO 规则说明：所有数字都由 EloCalculator 实时算出，
 * 前端不再硬编码，改了 K 因子或公式后官网 / 小程序展示的规则会跟着变。
 */
@Data
@Builder
public class EloRulesResponse {

    /** 新用户初始积分 */
    private Integer defaultRating;

    /** 一句话原则 */
    private String principle;

    /** 期望胜率与积分变化公式 */
    private String formula;

    /** K 因子档位（含该档位下的典型加减分） */
    @JsonProperty("kRules")
    private List<KRule> kRules;

    /** 典型对局场景（真实 settle 计算） */
    private List<Scenario> scenarios;

    /** 取整规则等补充说明 */
    private List<String> notes;

    /** 双打细节说明 */
    private List<String> doublesNotes;

    /** 性别修正说明（跨赛制对局） */
    private List<String> genderNotes;

    /** 局内分差动态调整说明 */
    private List<String> marginNotes;

    @Data
    @Builder
    public static class KRule {
        /** 档位描述，如「≤ 20 场」 */
        private String games;
        private Integer k;
        /** 强方取胜加分（理论值，保留一位小数） */
        private Double strongWin;
        /** 强方落败扣分 */
        private Double strongLoss;
        /** 弱方取胜加分 */
        private Double weakWin;
        /** 弱方落败扣分 */
        private Double weakLoss;
    }

    @Data
    @Builder
    public static class Scenario {
        private String scene;
        /** 对阵描述，如「1400 vs 1200」 */
        private String matchup;
        /** 己方（matchup 中前者）期望胜率 */
        private Double expect;
        /** 己方取胜的积分变化 */
        private Double winDelta;
        /** 己方落败的积分变化 */
        private Double lossDelta;
        /** true = 同队每人都是这个变化（双打） */
        private Boolean perPlayer;
        private String note;
    }
}
