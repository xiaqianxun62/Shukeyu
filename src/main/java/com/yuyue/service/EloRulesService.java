package com.yuyue.service;

import com.yuyue.common.Constants;
import com.yuyue.config.EloProperties;
import com.yuyue.dto.EloRulesResponse;
import com.yuyue.engine.EloCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ELO 规则说明：不写死任何数字，全部走 EloCalculator 的真实实现算出来。
 * 这样调整 K 因子（yuyue.elo.k-*）或期望公式时，官网与小程序展示的规则会同步变化，
 * 不会出现「文档与引擎不一致」的漂移。
 */
@Service
@RequiredArgsConstructor
public class EloRulesService {

    /** K 档位的代表场次：kFactor 只看档位，取档内任意值都一样 */
    private static final int SAMPLE_NEW = 10;
    private static final int SAMPLE_MID = 40;
    private static final int SAMPLE_PRO = 80;

    /** 场景里的积分差：200 分 */
    private static final int GAP = 200;

    private final EloCalculator eloCalculator;
    private final EloProperties props;

    public EloRulesResponse rules() {
        int base = props.getDefaultRating();
        int edge = (int) Math.round(props.getMaleVsFemaleEdge());
        return EloRulesResponse.builder()
                .defaultRating(base)
                .principle("ELO 浮动制：赢强的多加分、赢弱的少加分、输给弱的扣分多")
                .formula("E = 1 / (1 + 10^(有效分差 / 400))，Δ = K × (S − E) + 分差加成")
                .kRules(List.of(
                        kRule("≤ 20 场", SAMPLE_NEW),
                        kRule("21 – 60 场", SAMPLE_MID),
                        kRule("> 60 场", SAMPLE_PRO)))
                .scenarios(scenarios(base))
                .notes(List.of(
                        "K 表为理论值保留一位小数，真实结算按四舍五入取整（如 9.6 → +10）",
                        "初始积分 " + base + " 分，历史场次按已结算对局数累计"))
                .doublesNotes(List.of(
                        "同队两人使用同一个组合期望 E（按队伍平均分计算），但各自按自己的 K（各自历史场次）更新积分",
                        "ELO 自带防刷：同一组合反复刷分，积分差拉开后强方几乎不得分"))
                .genderNotes(List.of(
                        "跨赛制不再只看积分：按队内每个人的性别折算「跨性别对抗成本」得到 handicap（正 = 吃亏，负 = 占便宜），折进有效分差",
                        "男生场上对手有女生即占便宜，女生场上对手有男生即吃亏，各按对手异性占比线性折算，默认每人 " + edge + " 分",
                        "同赛制对局双方 handicap 完全抵消，期望仍是 0.5；未填写性别的球友按中性处理，不参与修正"))
                .marginNotes(List.of(
                        "局内分差动态调整：净胜分超过触发线（21 分制下约净胜 5 分）后开始加分，净胜分越多加成越高，21 分打满时胜方最多额外拿 K × "
                                + trim(props.getMarginBonusRate()) + " 分",
                        "加成严格零和——胜方加多少，负方就扣多少，不会凭空产生积分；未录入比分的对局不受影响"))
                .build();
    }

    /** 单个 K 档位：用真实 kFactor + expected 反推强/弱双方胜负的积分变化 */
    private EloRulesResponse.KRule kRule(String label, int sampleGames) {
        int k = eloCalculator.kFactor(sampleGames);
        int strong = props.getDefaultRating() + GAP;
        int weak = props.getDefaultRating();
        double expectStrong = eloCalculator.expected(strong, weak);
        return EloRulesResponse.KRule.builder()
                .games(label)
                .k(k)
                .strongWin(round1(k * (1 - expectStrong)))
                .strongLoss(round1(-k * expectStrong))
                .weakWin(round1(k * expectStrong))
                .weakLoss(round1(-k * (1 - expectStrong)))
                .build();
    }

    private List<EloRulesResponse.Scenario> scenarios(int base) {
        List<EloRulesResponse.Scenario> list = new ArrayList<>();
        list.add(singles("势均力敌", base, base, "五五开，胜负波动最大"));
        list.add(singles("强打弱", base + GAP, base, "强胜弱加分少，强负弱扣分多"));
        list.add(singles("弱胜强", base, base + GAP, "爆冷奖励大"));
        list.add(doubles(base));
        list.add(crossFormat(base, "男双打女双", false,
                "同分也会向男双倾斜，跨赛制取胜拿不到满额分"));
        list.add(crossFormat(base, "男双打混双", true,
                "混双女生要顶男生的位置，期望被小幅压低"));
        list.add(marginDoubles(base, 21, 19, "苦战局", "净胜 2 分未达触发线，只有基础分没有分差加成"));
        list.add(marginDoubles(base, 21, 5, "碾压局", "净胜 16 分，胜方额外拿到接近上限的分差加成"));
        return list;
    }

    /** 单打场景：分别用真实 settle 算「己方胜」与「己方负」 */
    private EloRulesResponse.Scenario singles(String scene, int self, int rival, String note) {
        EloCalculator.PlayerInput me = new EloCalculator.PlayerInput(1L, self, SAMPLE_NEW);
        EloCalculator.PlayerInput opponent = new EloCalculator.PlayerInput(2L, rival, SAMPLE_NEW);
        int win = deltaOf(me, opponent, Constants.WINNER_A);
        int loss = deltaOf(me, opponent, Constants.WINNER_B);
        return EloRulesResponse.Scenario.builder()
                .scene(scene)
                .matchup(self + " vs " + rival)
                .expect(round2(eloCalculator.expected(self, rival)))
                .winDelta((double) win)
                .lossDelta((double) loss)
                .perPlayer(false)
                .note(note)
                .build();
    }

    /** 己方单打一场的积分变化：winner=1 己方胜，winner=2 己方负 */
    private int deltaOf(EloCalculator.PlayerInput me, EloCalculator.PlayerInput opponent, int winner) {
        return eloCalculator.settle(List.of(me), List.of(opponent), winner)[0].players().get(0).delta();
    }

    /** 双打场景：A 队（强 + 中）vs B 队（中 + 弱），同队 K 相同则每人变化相同 */
    private EloRulesResponse.Scenario doubles(int base) {
        int strong = base + GAP;
        int mid = base;
        int low = base - GAP;
        List<EloCalculator.PlayerInput> teamA = List.of(
                new EloCalculator.PlayerInput(1L, strong, SAMPLE_NEW),
                new EloCalculator.PlayerInput(2L, mid, SAMPLE_NEW));
        List<EloCalculator.PlayerInput> teamB = List.of(
                new EloCalculator.PlayerInput(3L, mid, SAMPLE_NEW),
                new EloCalculator.PlayerInput(4L, low, SAMPLE_NEW));
        EloCalculator.TeamResult[] win = eloCalculator.settle(teamA, teamB, Constants.WINNER_A);
        EloCalculator.TeamResult[] loss = eloCalculator.settle(teamA, teamB, Constants.WINNER_B);
        int avgA = (strong + mid) / 2;
        int avgB = (mid + low) / 2;
        return EloRulesResponse.Scenario.builder()
                .scene("双打示例")
                .matchup("均值 " + avgA + " vs " + avgB)
                .expect(round2(win[0].expected()))
                .winDelta((double) win[0].players().get(0).delta())
                .lossDelta((double) loss[0].players().get(0).delta())
                .perPlayer(true)
                .note("A 队 (" + strong + ", " + mid + ") vs B 队 (" + mid + ", " + low + ")")
                .build();
    }

    /** 跨赛制场景：同分男双 vs 女双/混双，性别修正让男双期望高于 0.5 */
    private EloRulesResponse.Scenario crossFormat(int base, String scene, boolean mixedOpponent, String note) {
        List<EloCalculator.PlayerInput> teamA = List.of(
                new EloCalculator.PlayerInput(1L, base, SAMPLE_NEW, Constants.GENDER_MALE),
                new EloCalculator.PlayerInput(2L, base, SAMPLE_NEW, Constants.GENDER_MALE));
        List<EloCalculator.PlayerInput> teamB = mixedOpponent
                // 混双：男生 + 女生
                ? List.of(new EloCalculator.PlayerInput(3L, base, SAMPLE_NEW, Constants.GENDER_MALE),
                        new EloCalculator.PlayerInput(4L, base, SAMPLE_NEW, Constants.GENDER_FEMALE))
                // 女双：女生 + 女生
                : List.of(new EloCalculator.PlayerInput(3L, base, SAMPLE_NEW, Constants.GENDER_FEMALE),
                        new EloCalculator.PlayerInput(4L, base, SAMPLE_NEW, Constants.GENDER_FEMALE));
        String opponentLabel = mixedOpponent ? "混双 " : "女双 ";
        EloCalculator.TeamResult[] win = eloCalculator.settle(teamA, teamB, Constants.WINNER_A);
        EloCalculator.TeamResult[] loss = eloCalculator.settle(teamA, teamB, Constants.WINNER_B);
        return EloRulesResponse.Scenario.builder()
                .scene(scene)
                .matchup("男双 " + base + " vs " + opponentLabel + base)
                .expect(round2(win[0].expected()))
                .winDelta((double) win[0].players().get(0).delta())
                .lossDelta((double) loss[0].players().get(0).delta())
                .perPlayer(true)
                .note(note)
                .build();
    }

    /** 局内分差场景：同一个对手，净胜分不同带来的加分差异 */
    private EloRulesResponse.Scenario marginDoubles(int base, int scoreA, int scoreB,
                                                    String scene, String note) {
        List<EloCalculator.PlayerInput> teamA = List.of(
                new EloCalculator.PlayerInput(1L, base + GAP, SAMPLE_NEW, Constants.GENDER_MALE),
                new EloCalculator.PlayerInput(2L, base, SAMPLE_NEW, Constants.GENDER_MALE));
        List<EloCalculator.PlayerInput> teamB = List.of(
                new EloCalculator.PlayerInput(3L, base, SAMPLE_NEW, Constants.GENDER_MALE),
                new EloCalculator.PlayerInput(4L, base - GAP, SAMPLE_NEW, Constants.GENDER_MALE));
        EloCalculator.TeamResult[] win = eloCalculator.settle(teamA, teamB, Constants.WINNER_A, scoreA, scoreB);
        EloCalculator.TeamResult[] loss = eloCalculator.settle(teamA, teamB, Constants.WINNER_B, scoreA, scoreB);
        return EloRulesResponse.Scenario.builder()
                .scene(scene)
                .matchup("均值 " + (base + GAP / 2) + " vs " + (base - GAP / 2) + "，" + scoreA + ":" + scoreB)
                .expect(round2(win[0].expected()))
                .winDelta((double) win[0].players().get(0).delta())
                .lossDelta((double) loss[0].players().get(0).delta())
                .perPlayer(true)
                .note(note)
                .build();
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String trim(double value) {
        return String.valueOf(Math.round(value * 100) / 100.0);
    }
}
