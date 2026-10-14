package com.yuyue.engine;

import com.yuyue.engine.TournamentEngine.GroupStanding;
import com.yuyue.engine.TournamentEngine.Pair;
import com.yuyue.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锦标赛引擎测试：随机抽签、组内单循环、组内排名、淘汰赛 2 的幂签位
 *
 * <p>这些是赛制最容易出错的地方——抽签漏人 / 组内少打一场 / 同组首轮内耗 / 签位数凑不齐，
 * 一旦错了整套赛程就废了，所以逐条钉死。
 */
class TournamentEngineTest {

    private final TournamentEngine engine = new TournamentEngine();

    private static List<Long> ids(int n) {
        return LongStream.rangeClosed(1, n).boxed().toList();
    }

    @Test
    @DisplayName("抽签分组：不漏人、不重复，每组最多 groupSize 队")
    void drawGroupsKeepsEveryone() {
        for (int n : List.of(8, 13, 32, 64)) {
            List<List<Long>> groups = engine.drawGroups(ids(n), 4);
            List<Long> flat = groups.stream().flatMap(List::stream).toList();

            assertEquals(n, flat.size(), n + " 人应全部落组");
            assertEquals(n, new HashSet<>(flat).size(), "不应有重复落组");
            groups.forEach(g -> assertTrue(g.size() >= 2 && g.size() <= 4,
                    "组人数必须在 2~4 之间，实际 " + g.size()));
        }
    }

    @Test
    @DisplayName("抽签分组：整除时组数 = 人数 / 每组人数")
    void drawGroupsCount() {
        assertEquals(8, engine.drawGroups(ids(32), 4).size());
        assertEquals(16, engine.drawGroups(ids(64), 4).size());
        assertEquals(4, engine.drawGroups(ids(8), 2).size());
    }

    @Test
    @DisplayName("组内单循环：n 队打n-1 轮，且任意两队恰好交手一次")
    void roundRobinIsComplete() {
        for (int n : List.of(3, 4, 5, 8)) {
            List<List<Pair>> schedule = engine.roundRobin(ids(n));
            // 偶数队打 n-1 轮；奇数队补虚拟队制造轮空，所以是 n 轮
            int expectedRounds = (n % 2 == 0) ? n - 1 : n;
            assertEquals(expectedRounds, schedule.size(), n + " 队应有 " + expectedRounds + " 轮");

            List<String> pairs = new ArrayList<>();
            schedule.forEach(r -> r.forEach(p -> pairs.add(Math.min(p.a(), p.b()) + "-" + Math.max(p.a(), p.b()))));
            assertEquals(n * (n - 1) / 2, pairs.size(), "总场次应为 C(n,2)");
            assertEquals(pairs.size(), new HashSet<>(pairs).size(), "任意两队只能交手一次");
            assertTrue(schedule.stream().flatMap(List::stream).noneMatch(p -> p.a() == null || p.b() == null),
                    "轮空位不应产出对阵");
        }
    }

    @Test
    @DisplayName("组内排名：胜场优先，其次净胜分，全同则并列")
    void rankGroupByWinThenDiff() {
        List<TournamentEngine.GroupRanked> ranked = engine.rankGroup(List.of(
                new GroupStanding(1L, 2, 1, 5),
                new GroupStanding(2L, 2, 1, 12),
                new GroupStanding(3L, 1, 2, 30),
                new GroupStanding(4L, 2, 1, 12)));

        // 排序结果：2号与 4号同为 2 胜 12 分差，并列第一；1 号 2 胜 5 分差第三；3 号 1 胜垫底
        RankLookup rank = new RankLookup(ranked);
        assertEquals(1, rank.of(2L), "同胜场看净胜分，净胜 12 应排第一");
        assertEquals(1, rank.of(4L), "胜场与净胜分都相同，应与 2 号并列");
        assertEquals(3, rank.of(1L), "同为 2 胜但净胜分低，排第三");
        assertEquals(4, rank.of(3L), "只有 1 胜的垫底");
    }

    @Test
    @DisplayName("淘汰赛规模：只接受 4/8/16/32/64/128，其余报错")
    void knockoutSizeMustBePowerOfTwo() {
        assertEquals(4, engine.knockoutSize(4));
        assertEquals(16, engine.knockoutSize(16));
        assertEquals(64, engine.knockoutSize(64));

        // 晋级人数不是 2 的幂 → 签位会留空，直接判错让管理员改配置
        assertThrows(BizException.class, () -> engine.knockoutSize(18));
        assertThrows(BizException.class, () -> engine.knockoutSize(20));
        // 少于 4 队打不了淘汰赛
        assertThrows(BizException.class, () -> engine.knockoutSize(2));
    }

    @Test
    @DisplayName("淘汰赛配对：胜者两两成对，互不重复")
    void shufflePairsIsPerfectMatching() {
        List<Pair> pairs = engine.shufflePairs(ids(16));
        assertEquals(8, pairs.size());

        Set<Long> used = pairs.stream().flatMap(p -> List.of(p.a(), p.b()).stream())
                .collect(Collectors.toSet());
        assertEquals(16, used.size(), "16 个胜者应各出现一次");
        assertThrows(BizException.class, () -> engine.shufflePairs(ids(7)), "奇数队无法配对");
    }

    @Test
    @DisplayName("组名：A~Z 后接 AA")
    void groupNo() {
        assertEquals("A", engine.groupNo(1));
        assertEquals("Z", engine.groupNo(26));
        assertEquals("AA", engine.groupNo(27));
    }

    @Test
    @DisplayName("下一轮规模逐次折半直到四强")
    void nextRoundSizeHalves() {
        assertEquals(16, engine.nextRoundSize(32));
        assertEquals(8, engine.nextRoundSize(16));
        assertEquals(4, engine.nextRoundSize(8));
    }

    /** 测试用：entryId → 名次 的查询小工具 */
    private record RankLookup(List<TournamentEngine.GroupRanked> ranked) {
        int of(long entryId) {
            return ranked.stream().filter(r -> r.entryId() == entryId)
                    .findFirst().orElseThrow().rank();
        }
    }
}