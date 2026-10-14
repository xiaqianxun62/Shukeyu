package com.yuyue.engine;

import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 大型比赛赛制引擎（随机抽签 + 赛程生成）
 * <p>
 * 与 {@link RotationEngine} 的区别：本引擎<b>完全不看积分</b>，只做随机抽签与赛程编排，
 * 因为锦标赛的价值就在「势均力敌的对手全靠抽签运气」；同时<b>不接ELO</b>，
 * 任何名次都不回写 user.rating。
 * <p>
 * 赛制链路：
 * <pre>
 *   小组赛  随机抽签分组 → 组内单循环（每队 groupSize-1 场）
 *      ↓ 每组前 advancePerGroup 名晋级
 *   淘汰赛  逐轮推进：32 → 16 → 8 → 4（每轮用本轮胜者随机配对生成下一轮）
 *      ↓
 *   收官战  季军赛（半决赛 2 负者）+ 决赛 → 冠军 / 亚军 / 季军 / 第 4
 * </pre>
 * 纯计算，不落库：抽签结果与对阵由 TournamentService 落库，本类只负责算。
 */
@Slf4j
@Component
public class TournamentEngine {

    /* ---------------- 数据模型 ---------------- */

    /** 一场对阵的两支参赛单位（tournament_entry.id）；任一为null 表示轮空 / 待定 */
    public record Pair(Long a, Long b) {
    }

    /** 组内一条成绩：参赛单位 id + 已累计的胜负与净胜分 */
    public record GroupStanding(Long entryId, int win, int loss, int pointDiff) {
    }

    /** 组内最终排名：参赛单位 id + 组内名次（1 = 小组第一） */
    public record GroupRanked(Long entryId, int rank) {
    }

    /* ---------------- 抽签分组 ---------------- */

    /**
     * 按人数算组数：向上取整。
     * <p>
     * {@code ceil(total / groupSize)} 只是上界——直接按它切分会在「除不尽且余 1」时切出
     * 一个落单的小组（13 人分 4 组 → 4/4/4/1），那1 个人打不了单循环、等于被吞掉。
     * 真正的切分走 {@link #drawGroups} 的「均摊」策略，本方法用于提前算组数做签位校验。
     */
    public int groupCount(int total, int groupSize) {
        if (groupSize < 2) {
            throw new BizException(ErrorCode.PARAM_ERROR, "每组至少 2 队才能打小组赛");
        }
        return total < 2 ? 0 : (total + groupSize - 1) / groupSize;
    }

    /**
     * 随机抽签分组：先整体打散，再<b>尽量均摊</b>到各组。
     * <p>
     * 均摊而不是「每组塞满 groupSize」是刻意的：13 人分 4 队切成 4/3/3/3，
     * 而不是 4/4/4/1（最后那组只有 1 人，打不了比赛，人就被悄悄吞了）。
     * 组人数最多 groupSize，且不会出现只有 1 人的组。
     *
     * @param entryIds  该组别下的全部参赛单位 id
     * @param groupSize 每组队数上限
     * @return 每组内的参赛单位 id 列表，按组序排列
     */
    public List<List<Long>> drawGroups(List<Long> entryIds, int groupSize) {
        List<Long> pool = new ArrayList<>(entryIds);
        int count = groupCount(pool.size(), groupSize);
        if (count == 0) {
            return new ArrayList<>();
        }
        shuffle(pool);

        int base = pool.size() / count;
        int extra = pool.size() % count;
        List<List<Long>> groups = new ArrayList<>(count);
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            int size = base + (i < extra ? 1 : 0);
            groups.add(new ArrayList<>(pool.subList(cursor, cursor + size)));
            cursor += size;
        }
        return groups;
    }

    /**
     * 组内单循环赛程（circle method / 轮转法）。
     * <p>
     * 偶数队打 n-1 轮；奇数队补一支虚拟队制造轮空，因此是 n 轮（每轮 1 场真比赛）。
     * 返回的每一轮里都不含 null 对阵。
     *
     * @return 逐轮的对阵列表，总场次恒为 C(n, 2)，任意两队恰好交手一次
     */
    public List<List<Pair>> roundRobin(List<Long> entryIds) {
        List<Long> rot = new ArrayList<>(entryIds);
        if (rot.size() < 2) {
            return new ArrayList<>();
        }
        // 奇数队补一支虚拟队，制造轮空位，避免本轮有队闲着
        if (rot.size() % 2 == 1) {
            rot.add(null);
        }
        int n = rot.size();
        List<List<Pair>> schedule = new ArrayList<>();
        for (int r = 0; r < n - 1; r++) {
            List<Pair> pairs = new ArrayList<>();
            for (int i = 0; i < n / 2; i++) {
                Long a = rot.get(i);
                Long b = rot.get(n - 1 - i);
                if (a != null && b != null) {
                    pairs.add(new Pair(a, b));
                }
            }
            schedule.add(pairs);

            // 轮转：首位固定不动，末位提到第二位，队与对手错开
            Long last = rot.remove(n - 1);
            rot.add(1, last);
        }
        return schedule;
    }

    /**
     * 组内排名：胜场 desc → 净胜分 desc → 报名先后（entryId asc）兜底，保证结果稳定可复现。
     *
     * @return 带名次的参赛单位（rank 从 1 开始）
     */
    public List<GroupRanked> rankGroup(List<GroupStanding> standings) {
        List<GroupStanding> sorted = new ArrayList<>(standings);
        sorted.sort(Comparator
                .comparingInt(GroupStanding::win).reversed()
                .thenComparing(Comparator.comparingInt(GroupStanding::pointDiff).reversed())
                .thenComparingLong(GroupStanding::entryId));

        List<GroupRanked> result = new ArrayList<>(sorted.size());
        int rank = 1;
        for (int i = 0; i < sorted.size(); i++) {
            // 与上一名成绩完全相同则并列：沿用上一名的名次，不再递增
            if (i == 0 || !sameStanding(sorted.get(i - 1), sorted.get(i))) {
                rank = i + 1;
            }
            result.add(new GroupRanked(sorted.get(i).entryId(), rank));
        }
        return result;
    }

    private boolean sameStanding(GroupStanding a, GroupStanding b) {
        return a.win() == b.win() && a.pointDiff() == b.pointDiff();
    }

    /* ---------------- 淘汰赛签位 ---------------- */

    /**
     * 由晋级人数推导淘汰赛规模：向上取 2 的幂，并做上下限校验。
     * <p>
     * 淘汰赛每轮两两配对淘汰，规模必须是 2 的幂才能一轮轮打到底、不出「轮空」。
     *
     * @param advanceCount 晋级总人数
     * @return 2 的幂规模的参赛队数
     */
    public int knockoutSize(int advanceCount) {
        if (advanceCount < Constants.TOURNAMENT_MIN_KNOCKOUT_SIZE) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "晋级人数至少 " + Constants.TOURNAMENT_MIN_KNOCKOUT_SIZE
                            + " 人才打淘汰赛，当前 " + advanceCount + " 人");
        }
        if (advanceCount > Constants.TOURNAMENT_MAX_KNOCKOUT_SIZE) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "晋级人数最多 " + Constants.TOURNAMENT_MAX_KNOCKOUT_SIZE + " 人，当前 " + advanceCount + " 人");
        }
        int size = Integer.highestOneBit(advanceCount);
        if (size < advanceCount) {
            // 不是 2 的幂：签位会留空，直接判错，避免生成一堆轮空
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "淘汰赛需要 2 的幂规模（4/8/16/32/64/128），当前晋级 " + advanceCount
                            + " 人。可调整「每组人数 × 每组名额」凑成整幂");
        }
        return size;
    }

    /**
     * 淘汰赛下一轮随机配对：把本轮全部胜者打散后两两配对。
     * <p>
     * 不做任何种子位保护（同组不重复规避），符合「随机抽签」的赛制预期。
     *
     * @param winners 本轮胜者的参赛单位 id，数量必须为偶数
     */
    public List<Pair> shufflePairs(List<Long> winners) {
        if (winners == null || winners.size() < 2) {
            return new ArrayList<>();
        }
        if (winners.size() % 2 != 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "晋级队数为奇数，无法配对：" + winners.size());
        }
        List<Long> pool = new ArrayList<>(winners);
        shuffle(pool);

        List<Pair> pairs = new ArrayList<>(pool.size() / 2);
        for (int i = 0; i < pool.size(); i += 2) {
            pairs.add(new Pair(pool.get(i), pool.get(i + 1)));
        }
        return pairs;
    }

    /** 下一轮规模：折半，直到 4 强 */
    public int nextRoundSize(int currentSize) {
        return Math.max(currentSize / 2, 0);
    }

    /* ---------------- 组名 ---------------- */

    /**
     * 组序号转组名：1→A，26→Z，27→AA。
     * <p>
     * 纯字母编号（不用 1 / 2 / 3），和真实赛事的「A 组 / B 组」习惯一致。
     */
    public String groupNo(int seq) {
        if (seq < 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "组序号必须从 1 开始，当前 " + seq);
        }
        StringBuilder sb = new StringBuilder();
        int n = seq;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    /* ---------------- 内部工具 ---------------- */

    /** Fisher-Yates洗牌：原地打散，随机源用 ThreadLocalRandom 避免多线程共用一个 Random */
    private void shuffle(List<Long> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = ThreadLocalRandom.current().nextInt(i + 1);
            Long tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }
}