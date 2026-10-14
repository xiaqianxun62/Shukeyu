package com.yuyue.engine;

import com.yuyue.common.Constants;
import com.yuyue.config.EloProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ELO 积分计算器
 *
 * <p><b>基础规则</b>：赢强的多加分、赢弱的少加分、输给弱的扣分多。
 * <ul>
 *   <li>K 因子按个人历史场次衰减：≤20 场 K=40，21-60 场 K=24，&gt;60 场 K=16</li>
 *   <li>双打：同队两人共用组合期望 E（队伍平均分计算），但各自按自己的 K 更新积分</li>
 *   <li>防刷：积分差拉开后强方期望 E 趋近 1，胜方几乎不得分</li>
 * </ul>
 *
 * <p><b>性别修正</b>：纯积分比较默认「男女同分即五五开」，这在男双打女双、男双打混双时明显失真。
 * 这里按队内每个人的性别，逐人计算「跨性别对抗成本」再取队伍平均，得到一个 handicap 分
 * （正值吃亏、负值占便宜），折进期望公式的有效分差：
 * <pre>
 *   有效分差 gap = (对手均分 − 己方均分) + (己方 handicap − 对手 handicap)
 *   E = 1 / (1 + 10^(gap / 400))
 * </pre>
 * 由此赛制无需显式传入，完全由双方性别构成自动推导（默认 maleVsFemaleEdge = femaleVsMaleEdge = 100）：
 * <ul>
 *   <li>男双 vs 女双 同分 → handicap 分别 -100 / +100 → gap=-200 → E≈0.76</li>
 *   <li>男双 vs 混双 同分 → handicap 分别 -50 / +50 → gap=-100 → E≈0.64</li>
 *   <li>同赛制（男双 vs 男双、混双 vs 混双…）→ handicap 互相抵消 → 仍是 E=0.5</li>
 * </ul>
 * 用户未填性别（gender=0）时按中性处理，该项贡献为 0，整场退回纯积分比较。
 *
 * <p><b>局内分差动态调整</b>：21:19 的苦战和 21:5 的碾压不该拿一样的分。
 * 以净胜分占满格（21 分）的比例做线性映射，超过触发线的部分折算成加成，
 * 胜方加分、负方扣<b>同额</b>，因此严格零和（双方人数相同时总增量为 0），不会凭空产生分数。
 */
@Component
@RequiredArgsConstructor
public class EloCalculator {

    private final EloProperties props;

    /**
     * 单个玩家的对局输入。
     *
     * @param gender 0未知 1男 2女，仅用于性别修正；未传时按未知（中性）处理
     */
    public record PlayerInput(Long userId, int rating, int gamesPlayed, int gender) {
        public PlayerInput(Long userId, int rating, int gamesPlayed) {
            this(userId, rating, gamesPlayed, Constants.GENDER_UNKNOWN);
        }
    }

    /**
     * 单个玩家的结算输出。
     *
     * @param marginDelta 本场分差加成的部分（胜方正、负方负，与 delta 反向对称）
     */
    public record PlayerResult(Long userId, int ratingBefore, int ratingAfter,
                               int delta, int kFactor, double expected, int marginDelta) {
    }

    /**
     * @param genderAdjust 本队的性别修正 handicap 分（正值吃亏、负值占便宜），仅用于展示与排查
     */
    public record TeamResult(List<PlayerResult> players, double expected, double genderAdjust) {
    }

    /**
     * 结算一场对局（不含性别修正与分差加成，等价于旧版行为）。
     */
    public TeamResult[] settle(List<PlayerInput> teamA, List<PlayerInput> teamB, int winnerTeam) {
        return settle(teamA, teamB, winnerTeam, 0, 0);
    }

    /**
     * 结算一场对局（单打或双打通用，含性别修正与局内分差动态调整）。
     *
     * @param teamA      A 队成员
     * @param teamB      B 队成员
     * @param winnerTeam 胜方：1=A队 2=B队
     * @param scoreA     A 队本场比分；未录入（0 或 null）时不产生分差加成
     * @param scoreB     B 队本场比分
     */
    public TeamResult[] settle(List<PlayerInput> teamA, List<PlayerInput> teamB, int winnerTeam,
                               int scoreA, int scoreB) {
        if (teamA.isEmpty() || teamB.isEmpty()) {
            throw new IllegalArgumentException("对局双方队伍不能为空");
        }
        if (winnerTeam != 1 && winnerTeam != 2) {
            throw new IllegalArgumentException("胜方只能为 1(A队) 或 2(B队)");
        }

        // 组合期望 E：用队伍平均积分 + 性别 handicap 计算（双打同队共用同一个 E）
        double ratingA = teamA.stream().mapToInt(PlayerInput::rating).average().orElse(0);
        double ratingB = teamB.stream().mapToInt(PlayerInput::rating).average().orElse(0);
        double handicapA = genderHandicap(teamA, teamB);
        double handicapB = genderHandicap(teamB, teamA);
        double expectedA = expectedWithGender(ratingA, ratingB, handicapA, handicapB);
        double expectedB = 1.0 - expectedA;

        double scoreWin = winnerTeam == 1 ? 1.0 : 0.0;
        double scoreLose = 1.0 - scoreWin;

        // 局内分差加成：以双方参赛者的平均 K 为基准算一次，保证胜加负减同额
        int winScore = winnerTeam == 1 ? scoreA : scoreB;
        int loseScore = winnerTeam == 1 ? scoreB : scoreA;
        int marginBonus = marginBonus(winScore, loseScore, baseKFactor(teamA, teamB));

        return new TeamResult[]{
                settleTeam(teamA, expectedA, scoreWin, marginBonus, handicapA),
                settleTeam(teamB, expectedB, scoreLose, marginBonus, handicapB)
        };
    }

    private TeamResult settleTeam(List<PlayerInput> team, double expected, double score,
                                  int marginBonus, double genderAdjust) {
        int margin = score > 0.5 ? marginBonus : -marginBonus;
        List<PlayerResult> results = new ArrayList<>(team.size());
        for (PlayerInput p : team) {
            int k = kFactor(p.gamesPlayed());
            // 各自按自己的 K 更新基础分：delta = K * (S - E)
            int base = (int) Math.round(k * (score - expected));
            int delta = base + margin;
            results.add(new PlayerResult(p.userId(), p.rating(), p.rating() + delta, delta, k, expected, margin));
        }
        return new TeamResult(results, expected, genderAdjust);
    }

    /**
     * 期望胜率 E = 1 / (1 + 10^((对手分 - 己方分) / 400))
     */
    public double expected(double own, double opponent) {
        return 1.0 / (1.0 + Math.pow(10, (opponent - own) / 400.0));
    }

    /**
     * 带性别修正的期望胜率：handicap 为正表示己方因性别构成吃亏，期望会被压低。
     */
    public double expectedWithGender(double ratingOwn, double ratingOpp,
                                     double handicapOwn, double handicapOpp) {
        double gap = (ratingOpp - ratingOwn) + (handicapOwn - handicapOpp);
        return 1.0 / (1.0 + Math.pow(10, gap / 400.0));
    }

    /**
     * 性别修正 handicap（分）：正值表示本队因性别构成吃亏，负值表示占便宜。
     *
     * <p>逐人按「自己面对的异性对手占比」折算再取队伍平均：
     * <ul>
     *   <li>男生面对的对手里有女生 → 占便宜，记 −maleVsFemaleEdge × 对手女生占比</li>
     *   <li>女生面对的对手里有男生 → 吃亏，记 +femaleVsMaleEdge × 对手男生占比</li>
     *   <li>未填性别 → 0</li>
     * </ul>
     * 副作用：男双 vs 女双时女生那侧 handicap 为正、男生那侧为负，期望自然向男双倾斜；
     * 同赛制对局双方 handicap 完全抵消，E 仍为 0.5，不影响原有手感。
     */
    public double genderHandicap(List<PlayerInput> own, List<PlayerInput> opponent) {
        if (!props.isGenderAdjustEnabled() || own.isEmpty() || opponent.isEmpty()) {
            return 0;
        }
        int oppSize = opponent.size();
        int oppMales = 0;
        int oppFemales = 0;
        for (PlayerInput p : opponent) {
            if (p.gender() == Constants.GENDER_MALE) {
                oppMales++;
            } else if (p.gender() == Constants.GENDER_FEMALE) {
                oppFemales++;
            }
        }
        // 对手性别全未知：无从判断跨性别成本，整场按中性处理
        if (oppMales + oppFemales == 0) {
            return 0;
        }
        double maleShare = (double) oppMales / oppSize;
        double femaleShare = (double) oppFemales / oppSize;

        double sum = 0;
        for (PlayerInput p : own) {
            if (p.gender() == Constants.GENDER_MALE) {
                sum -= props.getMaleVsFemaleEdge() * femaleShare;
            } else if (p.gender() == Constants.GENDER_FEMALE) {
                sum += props.getFemaleVsMaleEdge() * maleShare;
            }
        }
        return sum / own.size();
    }

    /**
     * 局内分差加成：净胜分越大，胜方多得的分越多（胜方加、负方扣同额，严格零和）。
     *
     * <p>净胜分 ≤ 满格 × marginFloor 时视为苦战，不给加成（21 分制下约等于净胜 5 分以内）；
     * 超出后线性增长到满格时的 K × marginBonusRate 分。
     *
     * @param baseK 双方参赛者的平均 K，保证加成不偏向任何一方
     */
    public int marginBonus(int scoreWin, int scoreLose, int baseK) {
        if (!props.isMarginBonusEnabled() || baseK <= 0) {
            return 0;
        }
        int cap = props.getMarginWinScore();
        double floor = props.getMarginFloor();
        double rate = props.getMarginBonusRate();
        if (cap <= 0 || rate <= 0) {
            return 0;
        }
        int margin = Math.abs(scoreWin - scoreLose);
        if (margin <= 0) {
            return 0;
        }
        double ratio = Math.min(1.0, (double) margin / cap);
        double span = Math.max(1e-6, 1.0 - Math.min(floor, 0.99));
        double factor = Math.max(0.0, (ratio - floor) / span);
        if (factor <= 0) {
            return 0;
        }
        return (int) Math.round(baseK * rate * factor);
    }

    /** 双方全部参赛者的平均 K：作为分差加成的统一基准，避免两端K 不同时破坏零和 */
    private int baseKFactor(List<PlayerInput> teamA, List<PlayerInput> teamB) {
        double sum = 0;
        int count = 0;
        for (PlayerInput p : teamA) {
            sum += kFactor(p.gamesPlayed());
            count++;
        }
        for (PlayerInput p : teamB) {
            sum += kFactor(p.gamesPlayed());
            count++;
        }
        return count == 0 ? 0 : (int) Math.round(sum / count);
    }

    /**
     * K 因子按历史场次衰减：新手波动大、老手稳定
     */
    public int kFactor(int gamesPlayed) {
        if (gamesPlayed <= 20) {
            return props.getKNew();
        }
        if (gamesPlayed <= 60) {
            return props.getKMid();
        }
        return props.getKPro();
    }
}
