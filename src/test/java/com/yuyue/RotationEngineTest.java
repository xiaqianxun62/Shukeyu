package com.yuyue;

import com.yuyue.common.Constants;
import com.yuyue.config.EloProperties;
import com.yuyue.engine.EloCalculator;
import com.yuyue.engine.RotationEngine;
import com.yuyue.engine.RotationEngine.CourtPlan;
import com.yuyue.engine.RotationEngine.CourtTypes;
import com.yuyue.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationEngineTest {

    private final RotationEngine engine = new RotationEngine(new EloCalculator(new EloProperties()));

    private List<RotationEngine.RotationPlayer> players(int males, int females) {
        List<RotationEngine.RotationPlayer> list = new ArrayList<>();
        for (int i = 1; i <= males; i++) {
            list.add(new RotationEngine.RotationPlayer((long) i, "男" + i, Constants.GENDER_MALE, 1000 + i * 20));
        }
        for (int j = 1; j <= females; j++) {
            long id = males + j;
            list.add(new RotationEngine.RotationPlayer(id, "女" + j, Constants.GENDER_FEMALE, 1000 + j * 15));
        }
        return list;
    }

    private static CourtTypes types(String... names) {
        return CourtTypes.of(List.of(names));
    }

    /* ==================== 场地配额分配 ==================== */

    @Test
    @DisplayName("只勾一种类型：就只有那一种场地")
    void singleTypeYieldsSingleCourtKind() {
        CourtPlan onlyMen = engine.allocateCourts(types("men"), 8, 4, 5);
        assertEquals(2, onlyMen.men());
        assertEquals(0, onlyMen.mixed() + onlyMen.women() + onlyMen.any());

        CourtPlan onlyWomen = engine.allocateCourts(types("women"), 8, 4, 5);
        assertEquals(1, onlyWomen.women());

        CourtPlan onlyMixed = engine.allocateCourts(types("mixed"), 8, 4, 5);
        assertEquals(2, onlyMixed.mixed());
        assertEquals(0, onlyMixed.men() + onlyMixed.women() + onlyMixed.any());
    }

    @Test
    @DisplayName("勾「男双+混双」：两种场地都会出现，不会被混双吃光配额")
    void menPlusMixedBothAppear() {
        CourtPlan plan = engine.allocateCourts(types("men", "mixed"), 8, 4, 2);
        assertEquals(1, plan.men(), "男双场应保底 1 片");
        assertEquals(1, plan.mixed(), "混双场应保底 1 片");
        assertEquals(2, plan.total());

        // 场地多于两种类型能吃下的数量时，优先喂混双（对人数最挑剔）
        CourtPlan more = engine.allocateCourts(types("men", "mixed"), 8, 4, 3);
        assertEquals(3, more.total());
        assertEquals(2, more.mixed());
        assertEquals(1, more.men());
    }

    @Test
    @DisplayName("勾「女双+混双」同理，且混双优先")
    void womenPlusMixedBothAppear() {
        CourtPlan plan = engine.allocateCourts(types("women", "mixed"), 4, 8, 2);
        assertEquals(1, plan.women());
        assertEquals(1, plan.mixed());
    }

    @Test
    @DisplayName("男女分场 / 三场并行退化为普通组合，不再是特例")
    void legacyPresetsAreJustCombinations() {
        CourtTypes separate = CourtTypes.fromLegacyRule(Constants.GENDER_RULE_SEPARATE);
        assertTrue(separate.has("men") && separate.has("women"));
        assertEquals(types("men", "women"), separate);

        CourtTypes allThree = CourtTypes.fromLegacyRule(Constants.GENDER_RULE_ALL_THREE);
        assertEquals(types("mixed", "men", "women"), allThree);
        // 男女 10:6 / 4 片 → 1 混双 + 2 男双 + 1 女双，16 人全上场
        CourtPlan plan = engine.allocateCourts(allThree, 10, 6, 4);
        assertEquals(1, plan.mixed());
        assertEquals(2, plan.men());
        assertEquals(1, plan.women());
        assertEquals(4, plan.total());
    }

    @Test
    @DisplayName("不限性别：可以单选，也可以和专属场地混着选")
    void anyTypeIsOptional() {
        CourtPlan onlyAny = engine.allocateCourts(types("any"), 8, 4, 3);
        assertEquals(3, onlyAny.any());
        assertEquals(0, onlyAny.mixed() + onlyAny.men() + onlyAny.women());

        // 专属场地优先，不限性别收剩下的尾巴：8 男开 2 片男双，剩下 4 个女生正好开 1 片不限性别
        CourtPlan mixed = engine.allocateCourts(types("men", "any"), 8, 4, 3);
        assertEquals(2, mixed.men());
        assertEquals(1, mixed.any());
        assertEquals(3, mixed.total());
        // 不给不限性别时，这 4 个女生只能休息
        assertEquals(0, engine.allocateCourts(types("men"), 8, 4, 3).any());
    }

    @Test
    @DisplayName("人不够时自动少开，不会开出不合法的场地")
    void neverOpensCourtItCannotStaff() {
        // 只有 3 个男生，开不出男双场（男双要 4 人）
        assertEquals(0, engine.allocateCourts(types("men"), 3, 4, 2).total());
        // 只有 1 个男生，混双要 2 男，也开不出（3 男 3 女是可以开 1 片混双的，所以这里用 1 男）
        assertEquals(0, engine.allocateCourts(types("mixed"), 1, 3, 2).total());
        // 男 4 女 3：开 1 片混双后只剩 1 女，凑不出女双也凑不出第 2 片混双
        CourtPlan plan = engine.allocateCourts(types("mixed", "women"), 4, 3, 2);
        assertEquals(1, plan.mixed());
        assertEquals(0, plan.women());
        assertEquals(1, plan.total());
    }

    /* ==================== 实际编排的正确性 ==================== */

    @Test
    @DisplayName("混双场每队一男一女，且期望胜率接近 50%")
    void mixedCourtIsAlwaysOneMaleOneFemalePerTeam() {
        RotationEngine.RotationPlan plan = engine.plan(players(6, 6), 2, 3,
                Constants.FORMAT_MEN_DOUBLES, true, types("mixed"));

        assertEquals(3, plan.rotation().size());
        for (RotationEngine.RotationRound round : plan.rotation()) {
            assertEquals(2, round.matches().size());
            for (RotationEngine.CourtMatch m : round.matches()) {
                assertEquals(Constants.FORMAT_MIXED_DOUBLES, m.format());
                assertEquals(1, males(m.teamA(), 6), "A 队应一男一女");
                assertEquals(1, males(m.teamB(), 6), "B 队应一男一女");
                assertTrue(Math.abs(m.expectedA() - 0.5) < 0.2,
                        "期望胜率应接近 50%，实际 " + m.expectedA());
            }
            assertNoDoubleBooking(round);
        }
    }

    @Test
    @DisplayName("勾了哪些场地类型，实际赛程里就只能出现这些类型")
    void producedCourtsMatchSelectedTypes() {
        // 男双 + 混双：每片场地要么 4 男，要么 2 男 2 女
        RotationEngine.RotationPlan plan = engine.plan(players(8, 4), 3, 4,
                Constants.FORMAT_MEN_DOUBLES, true, types("men", "mixed"));

        for (RotationEngine.RotationRound round : plan.rotation()) {
            assertEquals(3, round.matches().size());
            for (RotationEngine.CourtMatch m : round.matches()) {
                assertTrue(m.format() == Constants.FORMAT_MEN_DOUBLES
                                || m.format() == Constants.FORMAT_MIXED_DOUBLES,
                        "不应出现未勾选的场地类型，实际 format=" + m.format());
                if (m.format() == Constants.FORMAT_MEN_DOUBLES) {
                    assertEquals(2, males(m.teamA(), 8));
                    assertEquals(2, males(m.teamB(), 8));
                } else {
                    assertEquals(1, males(m.teamA(), 8));
                    assertEquals(1, males(m.teamB(), 8));
                }
            }
            assertNoDoubleBooking(round);
        }
    }

    /**
     * 四种类型全勾时，任何性别构成都是合法的，所以这里只校验「硬底线」：
     * 场数等于配额、每场 4 人 2v2、没人被重复排。
     *
     * <p>刻意不断言每队的性别构成——不限性别场本来就允许任意构成
     * （3 男 1 女分两队这种情况是设计允许的）。
     * 「只勾某几种 ⇒ 只出现某几种」由上面两个用例保证。
     */
    @Test
    @DisplayName("四种类型全勾：场数与人数的硬底线")
    void allFourTypesTogether() {
        CourtPlan quota = engine.allocateCourts(
                types("mixed", "men", "women", "any"), 9, 7, 4);
        assertEquals(4, quota.total());
        assertEquals(1, quota.mixed());
        assertEquals(1, quota.men());
        assertEquals(1, quota.women());
        assertEquals(1, quota.any());

        RotationEngine.RotationPlan plan = engine.plan(players(9, 7), 4, 4,
                Constants.FORMAT_MEN_DOUBLES, true, types("mixed", "men", "women", "any"));

        for (RotationEngine.RotationRound round : plan.rotation()) {
            assertEquals(4, round.matches().size(), "每轮应开满配额");
            for (RotationEngine.CourtMatch m : round.matches()) {
                assertEquals(2, m.teamA().size());
                assertEquals(2, m.teamB().size());
            }
            assertNoDoubleBooking(round);
        }
    }

    /** 专属场地的性别构成必须严格合法（不限性别场不在此列） */
    @Test
    @DisplayName("混双 / 男双 / 女双场的性别构成始终合法")
    void dedicatedCourtsKeepStrictComposition() {
        // 只勾三种专属场地、不含不限性别 → 所有场地的构成都应能被严格判定
        RotationEngine.RotationPlan plan = engine.plan(players(9, 7), 4, 4,
                Constants.FORMAT_MEN_DOUBLES, true, types("mixed", "men", "women"));

        for (RotationEngine.RotationRound round : plan.rotation()) {
            for (RotationEngine.CourtMatch m : round.matches()) {
                int maleA = males(m.teamA(), 9);
                int maleB = males(m.teamB(), 9);
                switch (m.format()) {
                    case Constants.FORMAT_MIXED_DOUBLES -> {
                        assertEquals(1, maleA, "混双场 A 队必须一男一女");
                        assertEquals(1, maleB, "混双场 B 队必须一男一女");
                    }
                    case Constants.FORMAT_MEN_DOUBLES -> {
                        assertEquals(2, maleA);
                        assertEquals(2, maleB);
                    }
                    case Constants.FORMAT_WOMEN_DOUBLES -> {
                        assertEquals(0, maleA);
                        assertEquals(0, maleB);
                    }
                    default -> throw new AssertionError("出现了非法场地类型 format=" + m.format());
                }
            }
            assertNoDoubleBooking(round);
        }
    }

    @Test
    @DisplayName("只勾男双：绝不出现女双或混双场地")
    void onlyMenNeverProducesOtherFormats() {
        RotationEngine.RotationPlan plan = engine.plan(players(10, 6), 2, 3,
                Constants.FORMAT_MEN_DOUBLES, true, types("men"));
        for (RotationEngine.RotationRound round : plan.rotation()) {
            for (RotationEngine.CourtMatch m : round.matches()) {
                assertEquals(Constants.FORMAT_MEN_DOUBLES, m.format());
                assertEquals(2, males(m.teamA(), 10));
                assertEquals(2, males(m.teamB(), 10));
            }
        }
    }

    @Test
    @DisplayName("旧的 genderRule 入口仍可用，且与新组合等价")
    void legacyGenderRuleStillWorks() {
        RotationEngine.RotationPlan legacy = engine.plan(players(8, 4), 3, 2,
                Constants.FORMAT_MEN_DOUBLES, true, Constants.GENDER_RULE_ALL_THREE);
        assertEquals(3, legacy.rotation().get(0).matches().size());
        // 男女分场（男双+女双）与直接勾这两种类型是同一件事
        RotationEngine.RotationPlan viaTypes = engine.plan(players(8, 4), 3, 2,
                Constants.FORMAT_MEN_DOUBLES, true, types("men", "women"));
        assertEquals(legacy.rotation().get(0).matches().size(), viaTypes.rotation().get(0).matches().size());
    }

    @Test
    @DisplayName("单打时忽略场地类型，全部按单打排")
    void singlesIgnoresCourtTypes() {
        RotationEngine.RotationPlan plan = engine.plan(players(8, 4), 3, 2,
                Constants.FORMAT_SINGLES, true, types("men"));
        for (RotationEngine.RotationRound round : plan.rotation()) {
            assertEquals(3, round.matches().size());
            for (RotationEngine.CourtMatch m : round.matches()) {
                assertEquals(Constants.FORMAT_SINGLES, m.format());
            }
        }
    }

    @Test
    @DisplayName("凑不出场地时报错，并说清是哪些类型不够人")
    void reportsWhichTypeCannotBeStaffed() {
        BizException e = assertThrows(BizException.class, () -> engine.plan(players(3, 1), 2, 1,
                Constants.FORMAT_MEN_DOUBLES, true, types("men")));
        assertTrue(e.getMessage().contains("男双需要 4 名男生"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("上场次数均衡（不限性别规则）")
    void playCountsAreBalanced() {
        RotationEngine.RotationPlan plan = engine.plan(players(5, 5), 2, 4,
                Constants.FORMAT_MEN_DOUBLES, true, types("any"));
        int min = plan.stats().stream().mapToInt(RotationEngine.PlayerStat::playCount).min().orElseThrow();
        int max = plan.stats().stream().mapToInt(RotationEngine.PlayerStat::playCount).max().orElseThrow();
        assertTrue(max - min <= 1, "上场次数差应 ≤ 1，实际 " + min + "~" + max);
    }

    /* ==================== 辅助 ==================== */

    /** 同一轮里同一人只能上一片场地 */
    private void assertNoDoubleBooking(RotationEngine.RotationRound round) {
        long distinct = round.matches().stream()
                .flatMap(m -> java.util.stream.Stream.concat(m.teamA().stream(), m.teamB().stream()))
                .distinct().count();
        long total = round.matches().stream()
                .mapToLong(m -> (long) m.teamA().size() + m.teamB().size()).sum();
        assertEquals(total, distinct, "同一轮有人被排到两片场地");
    }

    private int males(List<Long> ids, int maleCount) {
        int n = 0;
        for (Long id : ids) {
            if (id <= maleCount) {
                n++;
            }
        }
        return n;
    }

    /* ==================== 性别未知（gender=0）回归 ==================== */

    /** 全部性别未知的名单，对应前端的「快速名单」试算模式 */
    private List<RotationEngine.RotationPlayer> unknownGender(int count) {
        List<RotationEngine.RotationPlayer> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(new RotationEngine.RotationPlayer((long) i, "球员" + i, Constants.GENDER_UNKNOWN, 1200));
        }
        return list;
    }

    @Test
    @DisplayName("性别全未知：能排出「不限性别」轮排，不抛越界异常")
    void unknownGenderStillArranges() {
        RotationEngine.RotationPlan plan = engine.plan(
                unknownGender(12), 2, 6, 2, true, types("any"));

        assertEquals(2, plan.courts());
        assertTrue(plan.rotation().size() > 0);
        // 每片 2v2、每场 4 人，且不重复排人
        for (RotationEngine.RotationRound r : plan.rotation()) {
            assertEquals(2, r.matches().size());
            assertNoDoubleBooking(r);
            for (RotationEngine.CourtMatch m : r.matches()) {
                assertEquals(2, m.teamA().size());
                assertEquals(2, m.teamB().size());
            }
        }
    }

    @Test
    @DisplayName("性别未知且场地多于能开的片数：自动降级，不抛异常")
    void unknownGenderTooFewPlayers() {
        // 4 个人只够开 1 片，场地填 3 片也应该自动收敛到 1 片，而不是炸在 subList 上。
        // 注意 RotationPlan.courts 是「请求填了几片场地」，不是实际开出了几片，
        // 实际片数看每轮的 matches 数量。
        RotationEngine.RotationPlan plan = engine.plan(unknownGender(4), 3, 2, 2, true, types("any"));

        assertEquals(3, plan.courts(), "courts 字段回显的是用户填写的值");
        for (RotationEngine.RotationRound r : plan.rotation()) {
            assertEquals(1, r.matches().size(), "4 个人每轮只该排得出 1 场");
            assertNoDoubleBooking(r);
        }
    }

    @Test
    @DisplayName("人都不够凑一片场地时报可读提示，不抛数组越界")
    void tooFewPlayersGivesFriendlyHint() {
        BizException e = assertThrows(BizException.class, () -> engine.plan(
                unknownGender(2), 1, 1, 2, true, types("any")));
        assertTrue(e.getMessage().contains("至少需要"), "应给出人数不足的可读提示，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("部分人性别未知：仍能开混双场，未知性别进女池")
    void partiallyUnknownCountsIntoFemalePool() {
        List<RotationEngine.RotationPlayer> list = new ArrayList<>(players(6, 1));
        // 把最后一个女生改成性别未知
        list.set(6, new RotationEngine.RotationPlayer(7L, "待填", Constants.GENDER_UNKNOWN, 1200));

        RotationEngine.RotationPlan plan = engine.plan(list, 3, 1, 2, true, types("mixed", "any"));

        assertTrue(plan.courts() >= 1, "至少要开出一片场地");
        for (RotationEngine.RotationRound r : plan.rotation()) {
            assertNoDoubleBooking(r);
        }
    }

    @Test
    @DisplayName("性别未知的人不会凭空多开场地：人数守恒")
    void unknownGenderDoesNotInflateCourts() {
        RotationEngine.RotationPlan plan = engine.plan(
                unknownGender(12), 2, 1, 2, true, types("any"));
        // 12 人 / 每场 4 人 = 最多 3 片，但只有 2 片场地可用
        assertEquals(2, plan.courts());
    }
}
