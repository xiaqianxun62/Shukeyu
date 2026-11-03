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

    /**
     * 组内单循环要打几轮。
     * <p>偶数队两两配对打 n-1 轮；奇数队补一支虚拟队制造轮空，因此是 n 轮。
     * 与 {@link #roundRobin} 实际产出的轮数严格一致（那边 rot.size() 已含虚拟队）。
     *
     * @param teamCount 组内实际队数
     */
    public int groupRounds(int teamCount) {
        if (teamCount < 2) {
            return 0;
        }
        return teamCount % 2 == 0 ? teamCount - 1 : teamCount;
    }

    /**
     * 按指定轮数生成组内赛程。
     *
     * <p>轮数与「满轮数」（{@link #groupRounds}）的关系：
     * <ul>
     *   <li><b>少打</b>（requestedRounds &lt; 满轮）：只取轮转法的前 N 轮，
     *       即部分循环——每队交手次数变少，排名依据变弱，可能出现并列；</li>
     *   <li><b>刚好</b>（= 满轮）：标准单循环；</li>
     *   <li><b>多打</b>（&gt; 满轮）：多循环（双循环及以上），
     *       每一遍重复同一套轮转配对，相邻两遍主客互换，
     *       于是任意两队在每一遍都恰好交手一次。</li>
     * </ul>
     *
     * @param entryIds      组内参赛单位 id
     * @param requestedRounds 指定轮数；≤ 0 表示打满单循环
     */
    public List<List<Pair>> roundRobin(List<Long> entryIds, int requestedRounds) {
        List<List<Pair>> base = roundRobin(entryIds);
        if (base.isEmpty()) {
            return base;
        }
        if (requestedRounds <= 0 || requestedRounds <= base.size()) {
            return requestedRounds <= 0
                    ? base
                    : new ArrayList<>(base.subList(0, requestedRounds));
        }

        // 多循环：重复满轮赛程，每遍主客互换
        List<List<Pair>> out = new ArrayList<>(base);
        boolean swap = true;
        while (out.size() < requestedRounds) {
            for (List<Pair> round : base) {
                if (out.size() >= requestedRounds) {
                    break;
                }
                List<Pair> copy = new ArrayList<>(round.size());
                for (Pair p : round) {
                    copy.add(swap ? new Pair(p.b(), p.a()) : p);
                }
                out.add(copy);
            }
            swap = !swap;
        }
        return out;
    }

    /**
     * 淘汰赛规模推导。
     *
     * <p>管理员可以指定淘汰赛轮数（{@code requestedRounds}，不含季军赛 / 决赛），
     * 对应规模为 2^(轮数+1)。但规模必须放得下全部晋级队，
     * 所以实际规模取「能容纳晋级队的最小 2 的幂」与「指定轮数对应规模」的较大者：
     * <ul>
     *   <li>晋级 6 队、指定八强轮（2 轮 → 规模 8）：规模 8，首轮 2 支轮空；</li>
     *   <li>晋级 16 队、指定四强轮（1 轮 → 规模 4）：放不下，自动扩容到 16 强（3 轮）；</li>
     *   <li>晋级 16 队、指定十六强轮（3 轮 → 规模 16）：刚好，无轮空。</li>
     * </ul>
     *
     * @param advanceCount   晋级队数
     * @param requestedRounds 指定轮数；≤ 0 表示自动（按晋级人数推导）
     */
    public int knockoutBracketSize(int advanceCount, int requestedRounds) {
        if (advanceCount < 2) {
            return 0;
        }
        int fit = Integer.highestOneBit(advanceCount);
        if (fit != advanceCount) {
            fit <<= 1;
        }
        if (requestedRounds >= 1) {
            // 规模上限 256（见 Constants），轮数再大也不会溢出
            int rounds = Math.min(requestedRounds, 7);
            int want = 1 << (rounds + 1);
            if (want > fit) {
                return want;
            }
        }
        return fit;
    }

    /**
     * 淘汰赛首轮签位：晋级队按排名填入位置，配对为 (i, size-1-i)。
     *
     * <p>沿用「标准种子」思路：排名靠前的队放左半区，靠后的放右半区，
     * 于是首轮强队对弱队，且同组的两队（排名相邻但隔着一个组距）不会首轮相遇。
     *
     * <p>位置多于晋级队时多出来的就是<b>轮空</b>——该位置在签表里留空，
     * 对应队伍首轮直接晋级。
     *
     * @param ranked 按（小组名次 → 组名 → id）排好序的晋级队 id
     * @param size   签位总数（2 的幂）
     */
    public List<Pair> seedPairs(List<Long> ranked, int size) {
        if (size < 2 || ranked.isEmpty()) {
            return new ArrayList<>();
        }
        Long[] positions = new Long[size];
        int half = size / 2;
        int leftCount = Math.min((ranked.size() + 1) / 2, half);
        for (int i = 0; i < ranked.size(); i++) {
            if (i < leftCount) {
                positions[i] = ranked.get(i);
            } else {
                int idx = half + (i - leftCount);
                if (idx < size) {
                    positions[idx] = ranked.get(i);
                }
            }
        }
        List<Pair> pairs = new ArrayList<>(half);
        for (int i = 0; i < half; i++) {
            pairs.add(new Pair(positions[i], positions[size - 1 - i]));
        }
        return pairs;
    }

    /**
     * 组内单循环的总场次：任意两队恰好交手一次，C(n,2)。
     */
    public int groupMatches(int teamCount) {
        if (teamCount < 2) {
            return 0;
        }
        return teamCount * (teamCount - 1) / 2;
    }

    /**
     * 每轮的真比赛场次：偶数队 k/2 场；奇数队补虚拟队后有 1 个空位，实际 (k-1)/2 场。
     */
    public int groupMatchesPerRound(int teamCount) {
        if (teamCount < 2) {
            return 0;
        }
        return teamCount % 2 == 0 ? teamCount / 2 : (teamCount - 1) / 2;
    }

    /**
     * 按指定轮数算组内总场次。
     * <p>每轮每队最多打 1 场，所以「轮数 × 每轮场次」就是总场次：
     * 部分循环按实际轮数算，多循环则每遍都是完整满轮结构。
     *
     * @param teamCount      组内实际队数
     * @param requestedRounds 指定轮数；≤ 0 表示打满单循环
     */
    public int groupTotalMatches(int teamCount, int requestedRounds) {
        int rounds = requestedRounds <= 0 ? groupRounds(teamCount) : requestedRounds;
        return rounds * groupMatchesPerRound(teamCount);
    }

    /**
     * 淘汰赛（不含季军赛 / 决赛）要打几轮。
     * <p>规模 2 的幂，逐轮折半：32 强 → 16 强 → 8 强 → 四强，共 4 轮。
     * 四强打满后转入收官战（季军赛 + 决赛），不再计入本方法。
     *
     * @param knockoutSize 淘汰赛规模（4 / 8 / 16 / 32 ...）
     */
    public int knockoutRounds(int knockoutSize) {
        if (knockoutSize < 4) {
            return 0;
        }
        return Integer.numberOfTrailingZeros(knockoutSize) - 1;
    }

    /**
     * 淘汰赛阶段（不含季军赛 / 决赛）的总场次。
     * <p>从 size 队一路打到四强：size/2 + size/4 + … + 4/2 = size - 2。
     * <p>注意<b>不是</b> size - 1：四强打完还剩 2 支队伍要打季军赛与决赛，
     * 那两场属于收官战，不计入本方法。
     *
     * @param knockoutSize 淘汰赛规模（4 / 8 / 16 / 32 ...）
     */
    public int knockoutMatches(int knockoutSize) {
        return knockoutSize < 4 ? 0 : knockoutSize - 2;
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