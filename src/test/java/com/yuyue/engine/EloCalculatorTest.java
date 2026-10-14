package com.yuyue.engine;

import com.yuyue.common.Constants;
import com.yuyue.config.EloProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ELO 计算规则测试：覆盖基础规则 + 性别修正 + 局内分差动态调整
 */
class EloCalculatorTest {

    private EloCalculator calculator;

    @BeforeEach
    void setUp() {
        EloProperties props = new EloProperties();
        props.setDefaultRating(1200);
        props.setKNew(40);
        props.setKMid(24);
        props.setKPro(16);
        props.setMaleVsFemaleEdge(100);
        props.setFemaleVsMaleEdge(100);
        props.setMarginWinScore(21);
        props.setMarginFloor(0.25);
        props.setMarginBonusRate(0.5);
        calculator = new EloCalculator(props);
    }

    private static final EloCalculator.PlayerInput P(Long id, int rating, int games) {
        return new EloCalculator.PlayerInput(id, rating, games);
    }

    private static final EloCalculator.PlayerInput G(Long id, int rating, int games, int gender) {
        return new EloCalculator.PlayerInput(id, rating, games, gender);
    }

    private static List<EloCalculator.PlayerInput> males(Long a, Long b, int rating, int games) {
        return List.of(G(a, rating, games, Constants.GENDER_MALE), G(b, rating, games, Constants.GENDER_MALE));
    }

    private static List<EloCalculator.PlayerInput> females(Long a, Long b, int rating, int games) {
        return List.of(G(a, rating, games, Constants.GENDER_FEMALE), G(b, rating, games, Constants.GENDER_FEMALE));
    }

    /* ==================== 基础规则 ==================== */

    @Test
    @DisplayName("势均力敌 1200 vs 1200：E=0.5，K=40 胜 +20 / 负 -20")
    void evenMatch() {
        EloCalculator.TeamResult[] r = calculator.settle(
                List.of(P(1L, 1200, 10)), List.of(P(2L, 1200, 10)), 1);

        assertEquals(0.5, r[0].expected(), 1e-9);
        assertEquals(+20, r[0].players().get(0).delta());
        assertEquals(-20, r[1].players().get(0).delta());
    }

    @Test
    @DisplayName("强打弱 1400 vs 1200：E=0.76，K=40 强胜 +9.6≈+10，强负 -30.4≈-30")
    void strongVsWeak() {
        EloCalculator.TeamResult[] r = calculator.settle(
                List.of(P(1L, 1400, 10)), List.of(P(2L, 1200, 10)), 1);

        assertEquals(0.76, r[0].expected(), 0.005);
        assertEquals(+10, r[0].players().get(0).delta());
        assertEquals(-10, r[1].players().get(0).delta());
    }

    @Test
    @DisplayName("弱胜强 1200 vs 1400：爆冷 +30.4≈+30，强负 -9.6≈-10")
    void upset() {
        EloCalculator.TeamResult[] r = calculator.settle(
                List.of(P(1L, 1200, 10)), List.of(P(2L, 1400, 10)), 1);

        assertEquals(+30, r[0].players().get(0).delta());
        assertEquals(-30, r[1].players().get(0).delta());
    }

    @Test
    @DisplayName("K 因子衰减：≤20 场 40，21-60 场 24，>60 场 16")
    void kFactorDecay() {
        assertEquals(40, calculator.kFactor(0));
        assertEquals(40, calculator.kFactor(20));
        assertEquals(24, calculator.kFactor(21));
        assertEquals(24, calculator.kFactor(60));
        assertEquals(16, calculator.kFactor(61));
    }

    @Test
    @DisplayName("双打：队伍均值算期望，同队共用 E，但各自按自己的 K 更新")
    void doublesSharedExpectedPerPlayerK() {
        EloCalculator.TeamResult[] r = calculator.settle(
                List.of(P(1L, 1400, 5), P(2L, 1200, 50)),
                List.of(P(3L, 1200, 5), P(4L, 1000, 50)),
                1);

        assertEquals(r[0].players().get(0).expected(), r[0].players().get(1).expected(), 1e-9);
        assertEquals(40, r[0].players().get(0).kFactor());
        assertEquals(24, r[0].players().get(1).kFactor());
        assertEquals(+10, r[0].players().get(0).delta());
        assertEquals(+6, r[0].players().get(1).delta());
    }

    @Test
    @DisplayName("防刷：积分差越大，强胜弱得分越少")
    void antiFarming() {
        EloCalculator.TeamResult[] bigGap = calculator.settle(
                List.of(P(1L, 1800, 10)), List.of(P(2L, 1200, 10)), 1);
        EloCalculator.TeamResult[] smallGap = calculator.settle(
                List.of(P(3L, 1400, 10)), List.of(P(4L, 1200, 10)), 1);

        int bigGapGain = bigGap[0].players().get(0).delta();
        int smallGapGain = smallGap[0].players().get(0).delta();
        assertTrue(bigGapGain < smallGapGain,
                "分差 600 时强方得分应少于分差 200 时: " + bigGapGain + " vs " + smallGapGain);
    }

    @Test
    @DisplayName("非法胜方值抛异常")
    void invalidWinner() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.settle(List.of(P(1L, 1200, 0)), List.of(P(2L, 1200, 0)), 3));
        assertThrows(IllegalArgumentException.class,
                () -> calculator.settle(List.of(), List.of(P(2L, 1200, 0)), 1));
    }

    /* ==================== 性别修正 ==================== */

    @Test
    @DisplayName("同赛制性别修正互相抵消：男双vs男双、女双vs女双、混双vs混双 均仍为E=0.5")
    void sameFormatKeepsEven() {
        EloCalculator.TeamResult[] men = calculator.settle(
                males(1L, 2L, 1600, 10), males(3L, 4L, 1600, 10), 1);
        assertEquals(0.5, men[0].expected(), 1e-9);
        assertEquals(0.0, men[0].genderAdjust(), 1e-9);

        EloCalculator.TeamResult[] women = calculator.settle(
                females(1L, 2L, 1600, 10), females(3L, 4L, 1600, 10), 1);
        assertEquals(0.5, women[0].expected(), 1e-9);

        List<EloCalculator.PlayerInput> mixedA = List.of(
                G(1L, 1600, 10, Constants.GENDER_MALE), G(2L, 1600, 10, Constants.GENDER_FEMALE));
        EloCalculator.TeamResult[] mixed = calculator.settle(mixedA, mixedA, 1);
        assertEquals(0.5, mixed[0].expected(), 1e-9);
    }

    @Test
    @DisplayName("男双打女双同分：handicap -100/+100，E≈0.76，胜方各+10 而非 +20")
    void menBeatWomenSameScore() {
        EloCalculator.TeamResult[] r = calculator.settle(
                males(1L, 2L, 1600, 10), females(3L, 4L, 1600, 10), 1);

        assertEquals(-100.0, r[0].genderAdjust(), 1e-9);
        assertEquals(100.0, r[1].genderAdjust(), 1e-9);
        assertEquals(0.76, r[0].expected(), 0.005);
        // 40 × (1 - 0.7597) = 9.6 → +10
        assertEquals(+10, r[0].players().get(0).delta());
        assertEquals(-10, r[1].players().get(0).delta());
        // 跨赛制取胜拿不到满额分
        assertTrue(r[0].players().get(0).delta() < 20, "跨赛制胜方加分应被压缩");
    }

    @Test
    @DisplayName("男双打混双同分：handicap -50/+50，E≈0.64，修正量小于对女双")
    void menBeatMixedSameScore() {
        List<EloCalculator.PlayerInput> mixed = List.of(
                G(3L, 1600, 10, Constants.GENDER_MALE), G(4L, 1600, 10, Constants.GENDER_FEMALE));
        EloCalculator.TeamResult[] r = calculator.settle(males(1L, 2L, 1600, 10), mixed, 1);

        assertEquals(-50.0, r[0].genderAdjust(), 1e-9);
        assertEquals(50.0, r[1].genderAdjust(), 1e-9);
        assertEquals(0.64, r[0].expected(), 0.005);
        // 40 × (1 - 0.6401) = 14.4 → +14
        assertEquals(+14, r[0].players().get(0).delta());
    }

    @Test
    @DisplayName("性别修正在「胜负互换」下对称：女双爆冷取胜拿更多分")
    void genderAdjustIsSymmetric() {
        EloCalculator.TeamResult[] r = calculator.settle(
                males(1L, 2L, 1600, 10), females(3L, 4L, 1600, 10), 2);

        // 胜方换了人，但期望仍判男双占便宜（E_A≈0.76）
        assertEquals(0.76, r[0].expected(), 0.005);
        // 女双爆冷：40 × (0 - 0.76) = -30.4 → 男双各 -30
        assertEquals(-30, r[0].players().get(0).delta());
        // 女双 40 × (1 - 0.24) = 30.4 → 各 +30，逆风爆冷奖励足
        assertEquals(+30, r[1].players().get(0).delta());
    }

    @Test
    @DisplayName("未填性别按中性处理：退回纯积分比较，E 仍为 0.5")
    void unknownGenderFallsBackToNeutral() {
        EloCalculator.TeamResult[] r = calculator.settle(
                males(1L, 2L, 1600, 10),
                List.of(P(3L, 1600, 10), P(4L, 1600, 10)),
                1);

        assertEquals(0.5, r[0].expected(), 1e-9);
        assertEquals(+20, r[0].players().get(0).delta());
    }

    @Test
    @DisplayName("关闭性别修正开关后退回旧行为（E=0.5、+20）")
    void genderAdjustCanBeDisabled() {
        EloProperties props = new EloProperties();
        props.setGenderAdjustEnabled(false);
        EloCalculator off = new EloCalculator(props);

        EloCalculator.TeamResult[] r = off.settle(
                males(1L, 2L, 1600, 10), females(3L, 4L, 1600, 10), 1);

        assertEquals(0.5, r[0].expected(), 1e-9);
        assertEquals(0.0, r[0].genderAdjust(), 1e-9);
        assertEquals(+20, r[0].players().get(0).delta());
    }

    @Test
    @DisplayName("单打男 vs 女同样吃性别修正")
    void singlesAlsoAdjusted() {
        EloCalculator.TeamResult[] r = calculator.settle(
                List.of(G(1L, 1500, 10, Constants.GENDER_MALE)),
                List.of(G(2L, 1500, 10, Constants.GENDER_FEMALE)),
                1);

        // 有效分差 = 0 + (-100 - 100) = -200 → E≈0.76
        assertEquals(0.76, r[0].expected(), 0.005);
    }

    /* ==================== 局内分差动态调整 ==================== */

    @Test
    @DisplayName("苦战 21:19 净胜 2 分不触发加成，与无比分完全一致")
    void closeMarginNoBonus() {
        EloCalculator.TeamResult[] withScore = calculator.settle(
                List.of(P(1L, 1200, 10)), List.of(P(2L, 1200, 10)), 1, 21, 19);
        EloCalculator.TeamResult[] withoutScore = calculator.settle(
                List.of(P(1L, 1200, 10)), List.of(P(2L, 1200, 10)), 1);

        assertEquals(0, withScore[0].players().get(0).marginDelta());
        assertEquals(withoutScore[0].players().get(0).delta(), withScore[0].players().get(0).delta());
        assertEquals(+20, withScore[0].players().get(0).delta());
    }

    @Test
    @DisplayName("碾压 21:5 净胜 16 分：胜方 +34 / 负方 -34，净胜分越多拿越多")
    void bigMarginGetsMore() {
        EloCalculator.TeamResult[] r = calculator.settle(
                List.of(P(1L, 1200, 10)), List.of(P(2L, 1200, 10)), 1, 21, 5);

        // f = (16/21 - 0.25) / 0.75 = 0.6825 → 加成 round(40 × 0.5 × 0.6825) = 14
        assertEquals(14, r[0].players().get(0).marginDelta());
        assertEquals(+34, r[0].players().get(0).delta());
        assertEquals(-34, r[1].players().get(0).delta());
    }

    @Test
    @DisplayName("分差加成严格零和：双方总增量恒为 0")
    void marginBonusIsZeroSum() {
        int[][] scores = {{21, 19}, {21, 10}, {21, 5}, {21, 0}, {21, 1}};
        for (int[] s : scores) {
            EloCalculator.TeamResult[] r = calculator.settle(
                    males(1L, 2L, 1500, 10), males(3L, 4L, 1300, 10), 1, s[0], s[1]);
            int total = r[0].players().stream().mapToInt(EloCalculator.PlayerResult::delta).sum()
                    + r[1].players().stream().mapToInt(EloCalculator.PlayerResult::delta).sum();
            assertEquals(0, total, s[0] + ":" + s[1] + " 总增量应为 0");
        }
    }

    @Test
    @DisplayName("净胜分单调递增，加成不递减，21:0 时打满 K × 0.5")
    void bonusGrowsWithMargin() {
        int prev = -1;
        for (int loseScore = 20; loseScore >= 0; loseScore--) {
            EloCalculator.TeamResult[] r = calculator.settle(
                    List.of(P(1L, 1200, 10)), List.of(P(2L, 1200, 10)), 1, 21, loseScore);
            int bonus = r[0].players().get(0).marginDelta();
            assertTrue(bonus >= prev, "净胜 " + (21 - loseScore) + " 分加成 " + bonus + " 不应小于上一档 " + prev);
            prev = bonus;
        }
        assertEquals(20, prev, "21:0 净胜 21 分打满时应达到 K × 0.5 = 20");
    }

    @Test
    @DisplayName("未录入比分（0:0 / null 归零）不产生任何加成，完全兼容旧逻辑")
    void noScoreMeansNoBonus() {
        EloCalculator.TeamResult[] zero = calculator.settle(
                List.of(P(1L, 1200, 10)), List.of(P(2L, 1200, 10)), 1, 0, 0);

        assertEquals(0, zero[0].players().get(0).marginDelta());
        assertEquals(+20, zero[0].players().get(0).delta());
        assertEquals(-20, zero[1].players().get(0).delta());
    }

    @Test
    @DisplayName("分差加成以双方平均 K 为基准，两端 K 不同时仍保持零和")
    void bonusUsesSharedBaseK() {
        // A 队老手 K=16，B 队新手 K=40，平均 K=28
        EloCalculator.TeamResult[] r = calculator.settle(
                List.of(P(1L, 1500, 80)), List.of(P(2L, 1300, 5)), 1, 21, 2);

        // 净胜 19 分 → f = (19/21-0.25)/0.75 = 0.873 → round(28 × 0.5 × 0.873) = 12
        assertEquals(12, r[0].players().get(0).marginDelta());
        assertEquals(-12, r[1].players().get(0).marginDelta());
    }

    @Test
    @DisplayName("关闭分差加成开关后退回基础分")
    void marginBonusCanBeDisabled() {
        EloProperties props = new EloProperties();
        props.setMarginBonusEnabled(false);
        EloCalculator off = new EloCalculator(props);

        EloCalculator.TeamResult[] r = off.settle(
                List.of(P(1L, 1200, 10)), List.of(P(2L, 1200, 10)), 1, 21, 5);

        assertEquals(0, r[0].players().get(0).marginDelta());
        assertEquals(+20, r[0].players().get(0).delta());
    }

    /* ==================== 组合场景 ==================== */

    @Test
    @DisplayName("男双 21:5 大胜女双：性别修正压缩期望 + 碾压加成，两者叠加仍零和")
    void genderAndMarginCombined() {
        EloCalculator.TeamResult[] r = calculator.settle(
                males(1L, 2L, 1600, 10), females(3L, 4L, 1600, 10), 1, 21, 5);

        // E≈0.76 → 基础 40 × 0.24 = 9.6 → +10
        assertEquals(10, r[0].players().get(0).delta() - r[0].players().get(0).marginDelta());
        // 碾压加成 round(40 × 0.5 × 0.6825) = 14
        assertEquals(14, r[0].players().get(0).marginDelta());
        // 最终每人 +24，负方 -24
        assertEquals(+24, r[0].players().get(0).delta());
        assertEquals(-24, r[1].players().get(0).delta());
    }

    @Test
    @DisplayName("高等级玩家碾压局仍不掉分（避免分差调整造成负增量）")
    void strongFavouriteCrushNeverLosesPoints() {
        EloCalculator.TeamResult[] r = calculator.settle(
                List.of(P(1L, 2000, 80)), List.of(P(2L, 1200, 80)), 1, 21, 1);

        assertTrue(r[0].players().get(0).delta() >= 0,
                "强方大比分获胜不应掉分，实际 " + r[0].players().get(0).delta());
    }
}
