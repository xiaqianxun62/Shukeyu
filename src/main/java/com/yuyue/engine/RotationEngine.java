package com.yuyue.engine;

import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 轮排引擎（场地轮转上场）
 * <p>
 * 场景：报名人数多于同时上场人数时，按「轮」安排谁上场、谁休息，
 * 典型如 12 人 2 片场地打双打：每轮 8 人上场、4 人休息，轮与轮之间自动轮换。
 * <p>
 * 三条硬约束：
 * <ol>
 *   <li>同一轮里同一人只能上一片场地；</li>
 *   <li>各人上场次数差 ≤ 1（优先让打得少的人上场）；</li>
 *   <li>尽量均衡实力：用 ELO 期望胜率（与真实结算同一个公式），让每场双方的期望胜率尽量接近 50%。</li>
 * </ol>
 * 两条软约束（通过代价函数做随机重启贪心，取最优）：
 * <ul>
 *   <li>避免重复搭档（权重最高）；</li>
 *   <li>避免重复对手。</li>
 * </ul>
 * 性别规则：双打可按「不分性别 / 男双 / 女双 / 混双」配队，落库赛制由每场的实际性别构成判定。
 * 纯计算，不落库：既可用于试算，也可用于真实球局。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RotationEngine {

    /** 与真实结算同一套 ELO 公式：编排时算的期望胜率，和打完结算时用的是一个算法 */
    private final EloCalculator eloCalculator;

    /** 随机重启次数：人数少、轮数少时开销可忽略 */
    private static final int RESTARTS = 60;
    /** 重复搭档惩罚 */
    private static final double W_PARTNER = 3.0;
    /** 重复对手惩罚 */
    private static final double W_OPPONENT = 1.0;
    /** 实力不均衡惩罚：按期望胜率偏离 50% 的程度计（差 200 分 ≈ 偏离 0.25） */
    private static final double W_IMBALANCE = 4.0;

    /* ---------------- 数据模型 ---------------- */

    public record RotationPlayer(Long userId, String label, int gender, int rating) {
    }

    /** 一片场地上的一场对局；expectedA 是 A 队的 ELO 期望胜率（0-1） */
    public record CourtMatch(int court, int format, List<Long> teamA, List<Long> teamB, double expectedA) {
    }

    /** 一轮：若干片场地同时开打 + 本轮休息的人 */
    public record RotationRound(int round, List<CourtMatch> matches, List<Long> resting) {
    }

    public record PartnerCount(Long userId, int times) {
    }

    /** 个人统计：上场 / 休息次数，以及和谁搭档过几次 */
    public record PlayerStat(Long userId, String label, int gender, int rating,
                             int playCount, int restCount, List<PartnerCount> partners) {
    }

    public record RotationPlan(int courts, int rounds, int format,
                                  List<RotationRound> rotation, List<PlayerStat> stats) {
        }

        /**
         * 三场并行（{@link Constants#GENDER_RULE_ALL_THREE}）一轮的场地配额：
         * 混双场 / 男双场 / 女双场各开几片。
         *
         * <p>三片场地加起来 = 这一轮同时开打的场数，人数恒为 4 × 三者之和。
         */
        public record CourtMix(int mixed, int men, int women) {
            public int total() {
                return mixed + men + women;
            }
        }

    /* ---------------- 主流程 ---------------- */

    /**
     * @param players         参与轮排的人（真实球局传报名名单即可）
     * @param courts          场地数（每轮同时开打的场数）
     * @param rounds          轮数
     * @param format          {@link Constants#FORMAT_SINGLES} 单打（2 人/场）或 {@link Constants#FORMAT_MEN_DOUBLES} 双打（4 人/场）
     * @param balanceStrength 是否按 ELO 期望胜率均衡组队；false 则纯随机配对
     */
    public RotationPlan plan(List<RotationPlayer> players, int courts, int rounds,
                             int format, boolean balanceStrength) {
        return plan(players, courts, rounds, format, balanceStrength, Constants.GENDER_RULE_ANY);
    }

    /**
     * 场地类型：本次允许开哪几种场地（双打时生效；单打一律单打，全部置 false）。
     *
     * <p>四种类型可任意组合，这就是「选男双就只有男双打男双、选男双+混双就两种都有」的模型：
     * <ul>
     *   <li>{@code mixed} 混双场：每片 2 男 2 女</li>
     *   <li>{@code men} 男双场：每片 4 男</li>
     *   <li>{@code women} 女双场：每片 4 女</li>
     *   <li>{@code any} 不限性别场：上场的 4 人性别任意（最宽松，排在最后兜底）</li>
     * </ul>
     * 全 false 表示没选任何类型，调用方应先兜住。
     */
    public record CourtTypes(boolean mixed, boolean men, boolean women, boolean any) {

        /** 值的优先级：越受限的越先占场地，把剩余的人留给最宽松的 any */
        static final List<String> PRIORITY = List.of("mixed", "men", "women", "any");

        public boolean isEmpty() {
            return !mixed && !men && !women && !any;
        }

        public boolean has(String type) {
            return switch (type) {
                case "mixed" -> mixed;
                case "men" -> men;
                case "women" -> women;
                case "any" -> any;
                default -> false;
            };
        }

        public CourtTypes enable(String type) {
            return switch (type) {
                case "mixed" -> new CourtTypes(true, men, women, any);
                case "men" -> new CourtTypes(mixed, true, women, any);
                case "women" -> new CourtTypes(mixed, men, true, any);
                case "any" -> new CourtTypes(mixed, men, women, true);
                default -> this;
            };
        }

        /** 按优先级排列的已选类型（去重后的顺序即优先级顺序） */
        public List<String> ordered() {
            return PRIORITY.stream().filter(this::has).toList();
        }

        /**
         * 解析前端传来的场地类型名（可多选、可乱序、可重复），忽略无法识别的值。
         * 全部无法识别时返回 {@link #none()}，由调用方决定怎么兜底。
         */
        public static CourtTypes of(Collection<String> names) {
            if (names == null || names.isEmpty()) {
                return none();
            }
            CourtTypes t = none();
            for (String raw : names) {
                if (raw == null) {
                    continue;
                }
                t = t.enable(raw.trim().toLowerCase(Locale.ROOT));
            }
            return t;
        }

        /**
         * 兼容旧的单选 genderRule（0~5）。这样「男女分场」「三场并行」不再是特例，
         * 它们只是 {男双,女双} 与 {混双,男双,女双} 两个组合而已。
         */
        public static CourtTypes fromLegacyRule(int genderRule) {
            return switch (genderRule) {
                case Constants.GENDER_RULE_MEN -> of(List.of("men"));
                case Constants.GENDER_RULE_WOMEN -> of(List.of("women"));
                case Constants.GENDER_RULE_MIXED -> of(List.of("mixed"));
                case Constants.GENDER_RULE_SEPARATE -> of(List.of("men", "women"));
                case Constants.GENDER_RULE_ALL_THREE -> of(List.of("mixed", "men", "women"));
                default -> of(List.of("any"));
            };
        }

        public static CourtTypes none() {
            return new CourtTypes(false, false, false, false);
        }
    }

    /**
     * 一轮里各类场地各开几片。这个配额整份轮排表共用一份（基于全员男女比），
     * 所以每轮的选人和配队都严格对齐，不会出现「这轮该开混双却临时凑不出人」的抖动。
     *
     * <p>同时给出选人配额：混双 / 男双场需要 {@link #maleNeed()} 个男生，
     * 混双 / 女双场需要 {@link #femaleNeed()} 个女生，剩下 {@code any * 4} 人进不限性别场。
     */
    public record CourtPlan(int mixed, int men, int women, int any) {

        public int total() {
            return mixed + men + women + any;
        }

        public int maleNeed() {
            return mixed * 2 + men * 4;
        }

        public int femaleNeed() {
            return mixed * 2 + women * 4;
        }

        /** 开不出场地时给用户看的说明，如「2 片混双 + 1 片男双」 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            appendPart(sb, mixed, "片混双");
            appendPart(sb, men, "片男双");
            appendPart(sb, women, "片女双");
            appendPart(sb, any, "片不限性别");
            return sb.toString();
        }

        private static void appendPart(StringBuilder sb, int count, String label) {
            if (count <= 0) {
                return;
            }
            if (!sb.isEmpty()) {
                sb.append(" + ");
            }
            sb.append(count).append(label);
        }
    }

    /**
     * 按选中的场地类型分配这一轮能开几片。
     *
     * <p>算法分两步：
     * <ol>
     *   <li><b>保底</b>：按「混双 → 男双 → 女双 → 不限性别」的优先级，每种<b>选中的</b>类型先尽量各开1 片。
     *       这一步是为了让「选了男双 + 混双」时两种场地都真的出现，
     *       而不是被混双吃光配额、男双一片都开不出来。</li>
     *   <li><b>补齐</b>：剩余场地按同一优先级轮转填充，谁还能开就谁开，直到开不出或场地用完。</li>
     * </ol>
     *
     * <p>混双排在最前是因为它对人数最挑剔（必须 2 男 2 女），先开满它，
     * 剩下的男 / 女必然同性别成堆，正好去开男双 / 女双；不限性别垫底，专收剩饭。
     *
     * @param maxCourts 最多能开几片（一般传场地数与人数共同决定的上限）
     */
    public CourtPlan allocateCourts(CourtTypes want, int males, int females, int maxCourts) {
        int left = Math.max(0, maxCourts);
        int mixed = 0;
        int men = 0;
        int women = 0;
        int any = 0;
        int restMale = males;
        int restFemale = females;

        // 第一步：每种选中的类型保底 1 片（人不够就跳过，不占用额度）
        for (String type : want.ordered()) {
            if (left <= 0) {
                break;
            }
            if ("mixed".equals(type) && 2 <= restMale && 2 <= restFemale) {
                mixed++;
                left--;
                restMale -= 2;
                restFemale -= 2;
            } else if ("men".equals(type) && 4 <= restMale) {
                men++;
                left--;
                restMale -= 4;
            } else if ("women".equals(type) && 4 <= restFemale) {
                women++;
                left--;
                restFemale -= 4;
            }
            // any 不参与保底：它只是「收剩饭」的兜底类型，不该抢走专属场地的名额
        }

        // 第二步：剩余场地按优先级轮转补齐
        boolean progress = true;
        while (left > 0 && progress) {
            progress = false;
            for (String type : want.ordered()) {
                if (left <= 0) {
                    break;
                }
                if ("mixed".equals(type) && 2 <= restMale && 2 <= restFemale) {
                    mixed++;
                    left--;
                    restMale -= 2;
                    restFemale -= 2;
                    progress = true;
                } else if ("men".equals(type) && 4 <= restMale) {
                    men++;
                    left--;
                    restMale -= 4;
                    progress = true;
                } else if ("women".equals(type) && 4 <= restFemale) {
                    women++;
                    left--;
                    restFemale -= 4;
                    progress = true;
                } else if ("any".equals(type) && 4 <= restMale + restFemale) {
                    any++;
                    left--;
                    // any 场地男女混用，从男生池先取，不够再从女生池补
                    int fromMale = Math.min(4, restMale);
                    restMale -= fromMale;
                    restFemale -= 4 - fromMale;
                    progress = true;
                }
            }
        }
        return new CourtPlan(mixed, men, women, any);
    }

    /**
     * @param genderRule 双打怎么配队（旧的单选规则，等价于只勾选一种场地类型）。
     *                   推荐改用 {@link #plan(List, int, int, int, boolean, CourtTypes)}。
     */
    public RotationPlan plan(List<RotationPlayer> players, int courts, int rounds,
                             int format, boolean balanceStrength, int genderRule) {
        return plan(players, courts, rounds, format, balanceStrength,
                CourtTypes.fromLegacyRule(genderRule));
    }

    /**
     * 主入口：按「勾选了哪些场地类型」生成轮排表。
     *
     * @param courtTypes 允许开哪几种场地（可多选）；单打时忽略
     */
    public RotationPlan plan(List<RotationPlayer> players, int courts, int rounds,
                             int format, boolean balanceStrength, CourtTypes courtTypes) {
        int perMatch = format == Constants.FORMAT_SINGLES ? 2 : 4;
        if (players == null || players.size() < perMatch) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "人数不足，至少需要 " + perMatch + " 人才能开始轮排");
        }
        if (courts < 1 || courts > 20) {
            throw new BizException(ErrorCode.PARAM_ERROR, "场地数需在 1-20 之间");
        }
        if (rounds < 1 || rounds > 30) {
            throw new BizException(ErrorCode.PARAM_ERROR, "轮数需在 1-30 之间");
        }
        // 单打时没有「男双 / 女双 / 混双」之分，场地类型不参与
        CourtTypes want = perMatch == 4 ? courtTypes : CourtTypes.of(List.of("any"));

        int males = countGender(players, Constants.GENDER_MALE);
        int females = players.size() - males;

        int upper = Math.min(courts, players.size() / perMatch);
        CourtPlan courtsPlan = allocateCourts(want, males, females, upper);
        int matchPerRound = courtsPlan.total();
        if (matchPerRound < 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, typesHint(want, males, females));
        }

        Map<Long, RotationPlayer> byId = new LinkedHashMap<>();
        players.forEach(p -> byId.put(p.userId(), p));

        Map<Long, Integer> playCount = new LinkedHashMap<>();
        Map<Long, Integer> restCount = new LinkedHashMap<>();
        Map<Long, Integer> lastRound = new LinkedHashMap<>();
        players.forEach(p -> {
            playCount.put(p.userId(), 0);
            restCount.put(p.userId(), 0);
            lastRound.put(p.userId(), 0);
        });

        Map<String, Integer> partnerHistory = new LinkedHashMap<>();
        Map<String, Integer> opponentHistory = new LinkedHashMap<>();

        List<RotationRound> out = new ArrayList<>();
        for (int r = 1; r <= rounds; r++) {
            List<RotationPlayer> picked = pickPlayers(players, courtsPlan, playCount, lastRound);
            List<CourtMatch> matches = buildMatches(picked, courtsPlan, perMatch, format,
                    balanceStrength, partnerHistory, opponentHistory, byId);

            // 应用本轮结果：更新上场 / 休息 / 搭档 / 对手历史
            List<Long> pickedIds = picked.stream().map(RotationPlayer::userId).toList();
            for (RotationPlayer p : picked) {
                playCount.merge(p.userId(), 1, Integer::sum);
                lastRound.put(p.userId(), r);
            }
            for (RotationPlayer p : players) {
                if (!pickedIds.contains(p.userId())) {
                    restCount.merge(p.userId(), 1, Integer::sum);
                }
            }
            for (CourtMatch m : matches) {
                recordPairs(partnerHistory, m.teamA());
                recordPairs(partnerHistory, m.teamB());
                for (Long a : m.teamA()) {
                    for (Long b : m.teamB()) {
                        opponentHistory.merge(key(a, b), 1, Integer::sum);
                    }
                }
            }
            out.add(new RotationRound(r, matches,
                    players.stream()
                            .map(RotationPlayer::userId)
                            .filter(id -> !pickedIds.contains(id))
                            .toList()));
        }

        List<PlayerStat> stats = buildStats(players, playCount, restCount, partnerHistory);
        log.info("轮排完成: 人数={}, 场地={}, 轮数={}, 每轮{}场({}), 场地类型={}",
                players.size(), courts, rounds, matchPerRound,
                courtsPlan.describe(), want.ordered());
        return new RotationPlan(courts, rounds, format, out, stats);
    }

    private String typesHint(CourtTypes want, int males, int females) {
        List<String> need = new ArrayList<>();
        if (want.mixed()) {
            need.add("混双需要男女各 2 人");
        }
        if (want.men()) {
            need.add("男双需要 4 名男生");
        }
        if (want.women()) {
            need.add("女双需要 4 名女生");
        }
        if (want.any()) {
            need.add("不限性别场地需要 4 人");
        }
        String detail = need.isEmpty() ? "至少要勾选一种场地类型" : String.join("；", need);
        return detail + "，当前男 " + males + " 人、女 " + females + " 人，无法开赛";
    }

    /**
     * 三场并行的场地分配：这一轮开几片混双、几片男双、几片女双。
     *
     * <p>已并入 {@link #allocateCourts(CourtTypes, int, int, int)}，此处不再单独保留。
     */

    /* ---------------- 选人：少打的先上，久没打的先上 ---------------- */

    /**
     * 按场地配额选人：专属场地（混双 / 男双 / 女双）从对应性别的池子里挑，
     * 不限性别场从剩下的人里补。池子内部仍按「打得少、上次早」的顺序轮换。
     *
     * <p>之所以先按配额分池再挑，而不是随机抽 4×场数 个人再想办法拼场地：
     * 只有按性别分池，才能保证「选了男双就只有男双打男双」这种约束<b>必然</b>成立，
     * 而不是靠事后校正。
     */
    private List<RotationPlayer> pickPlayers(List<RotationPlayer> players, CourtPlan plan,
                                             Map<Long, Integer> playCount,
                                             Map<Long, Integer> lastRound) {
        List<RotationPlayer> picked = new ArrayList<>();
        picked.addAll(take(byGender(players, Constants.GENDER_MALE), plan.maleNeed(), playCount, lastRound));
        picked.addAll(take(byGender(players, Constants.GENDER_FEMALE), plan.femaleNeed(), playCount, lastRound));
        if (plan.any() > 0) {
            // 不限性别场收剩下的：谁还没被选上都行，同样按轮换顺序挑
            Set<Long> chosen = picked.stream().map(RotationPlayer::userId).collect(Collectors.toSet());
            List<RotationPlayer> rest = players.stream()
                    .filter(p -> !chosen.contains(p.userId()))
                    .toList();
            picked.addAll(take(rest, plan.any() * 4, playCount, lastRound));
        }
        return picked;
    }

    private List<RotationPlayer> take(List<RotationPlayer> pool, int need,
                                      Map<Long, Integer> playCount,
                                      Map<Long, Integer> lastRound) {
        List<RotationPlayer> shuffled = new ArrayList<>(pool);
        // 先打乱，保证同等条件下轮换顺序不固定，避免总让名单末尾的人休息
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        shuffled.sort(Comparator
                .comparingInt((RotationPlayer p) -> playCount.getOrDefault(p.userId(), 0))
                .thenComparingInt(p -> lastRound.getOrDefault(p.userId(), 0)));
        return new ArrayList<>(shuffled.subList(0, Math.min(need, shuffled.size())));
    }

    private List<RotationPlayer> byGender(List<RotationPlayer> players, int gender) {
        List<RotationPlayer> result = new ArrayList<>();
        for (RotationPlayer p : players) {
            if (p.gender() == gender) {
                result.add(p);
            }
        }
        return result;
    }

    private int countGender(List<RotationPlayer> players, int gender) {
        return byGender(players, gender).size();
    }

    /* ---------------- 生成一轮的所有场地对局 ---------------- */

    /**
     * 按场地配额把上场的人铺到各片场地上。
     *
     * <p>一条通用路径覆盖全部组合：混双场吃 2 男 + 2 女、男双场吃 4 男、女双场吃 4 女，
     * 剩下的尾巴进不限性别场。因为 {@code pickPlayers} 已经按同一份配额分好了池子，
     * 这里的游标取法必然凑得齐，不会出现「混双场里坐了两个男」这类脏数据。
     *
     * <p>每次重启都会重新洗牌，再用代价函数挑最优的一次——见 {@link #cost}。
     */
    private List<CourtMatch> buildMatches(List<RotationPlayer> picked, CourtPlan plan, int perMatch,
                                          int format, boolean balanceStrength,
                                          Map<String, Integer> partnerHistory,
                                          Map<String, Integer> opponentHistory,
                                          Map<Long, RotationPlayer> byId) {
        List<CourtMatch> best = null;
        double bestCost = Double.MAX_VALUE;

        for (int t = 0; t < RESTARTS; t++) {
            List<CourtMatch> candidate = new ArrayList<>();
            if (perMatch == 4) {
                List<RotationPlayer> males = byGender(picked, Constants.GENDER_MALE);
                List<RotationPlayer> females = byGender(picked, Constants.GENDER_FEMALE);
                Collections.shuffle(males, ThreadLocalRandom.current());
                Collections.shuffle(females, ThreadLocalRandom.current());
                int maleCursor = 0;
                int femaleCursor = 0;
                int courtIdx = 1;

                // 混双场：每片 2 男 2 女，且必须跨性别配对（见 randomSplit 里的 MIXED 分支）
                for (int k = 0; k < plan.mixed(); k++) {
                    List<RotationPlayer> group =
                            new ArrayList<>(males.subList(maleCursor, maleCursor + 2));
                    maleCursor += 2;
                    group.addAll(females.subList(femaleCursor, femaleCursor + 2));
                    femaleCursor += 2;
                    candidate.add(randomSplit(group, courtIdx++, format,
                            Constants.GENDER_RULE_MIXED, byId));
                }
                // 男双场：全部是男生，怎么拆都合法
                for (int k = 0; k < plan.men(); k++) {
                    List<RotationPlayer> group =
                            new ArrayList<>(males.subList(maleCursor, maleCursor + 4));
                    maleCursor += 4;
                    candidate.add(randomSplit(group, courtIdx++, format,
                            Constants.GENDER_RULE_MEN, byId));
                }
                // 女双场：全部是女生
                for (int k = 0; k < plan.women(); k++) {
                    List<RotationPlayer> group =
                            new ArrayList<>(females.subList(femaleCursor, femaleCursor + 4));
                    femaleCursor += 4;
                    candidate.add(randomSplit(group, courtIdx++, format,
                            Constants.GENDER_RULE_WOMEN, byId));
                }
                // 不限性别场：收走两个池子剩下的尾巴，凑够 4 人一片
                if (plan.any() > 0) {
                    List<RotationPlayer> spare = new ArrayList<>(males.subList(maleCursor, males.size()));
                    spare.addAll(females.subList(femaleCursor, females.size()));
                    for (int k = 0; k < plan.any(); k++) {
                        List<RotationPlayer> group = new ArrayList<>(spare.subList(k * 4, (k + 1) * 4));
                        candidate.add(randomSplit(group, courtIdx++, format,
                                Constants.GENDER_RULE_ANY, byId));
                    }
                }
            } else {
                List<RotationPlayer> shuffled = new ArrayList<>(picked);
                Collections.shuffle(shuffled, ThreadLocalRandom.current());
                for (int k = 0; k < plan.total(); k++) {
                    List<RotationPlayer> group = new ArrayList<>(
                            shuffled.subList(k * perMatch, (k + 1) * perMatch));
                    candidate.add(randomSplit(group, k + 1, format, Constants.GENDER_RULE_ANY, byId));
                }
            }

            double cost = cost(candidate, partnerHistory, opponentHistory, balanceStrength);
            if (cost < bestCost) {
                bestCost = cost;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 在一场的人里随机挑一种合法组队方式：
     * 混双只在一男一女之间配（男强配女强 / 男强配女弱两种），其余三种两两组合都算合法。
     * 哪种最优由代价函数（重复搭档 / 重复对手 / ELO 均衡）在 60 次重启里挑出来。
     */
    private CourtMatch randomSplit(List<RotationPlayer> group, int court, int format,
                                   int genderRule, Map<Long, RotationPlayer> byId) {
        List<int[][]> ways = pairings(group, genderRule);
        int[][] way = ways.get(ThreadLocalRandom.current().nextInt(ways.size()));

        List<Long> teamA = new ArrayList<>();
        for (int idx : way[0]) {
            teamA.add(group.get(idx).userId());
        }
        List<Long> teamB = new ArrayList<>();
        for (int idx : way[1]) {
            teamB.add(group.get(idx).userId());
        }
        return new CourtMatch(court, formatOf(teamA, teamB, format, byId),
                teamA, teamB, expectedOf(teamA, teamB, byId));
    }

    /** 一场内部所有合法的组队方式（下标组合） */
    private List<int[][]> pairings(List<RotationPlayer> group, int genderRule) {
        if (group.size() <= 2) {
            return List.<int[][]>of(new int[][]{{0}, {1}});
        }
        if (genderRule == Constants.GENDER_RULE_MIXED) {
            List<Integer> m = new ArrayList<>();
            List<Integer> f = new ArrayList<>();
            for (int i = 0; i < group.size(); i++) {
                if (group.get(i).gender() == Constants.GENDER_MALE) {
                    m.add(i);
                } else {
                    f.add(i);
                }
            }
            if (m.size() == 2 && f.size() == 2) {
                return List.<int[][]>of(
                        new int[][]{{m.get(0), f.get(0)}, {m.get(1), f.get(1)}},
                        new int[][]{{m.get(0), f.get(1)}, {m.get(1), f.get(0)}});
            }
        }
        return List.<int[][]>of(
                new int[][]{{0, 1}, {2, 3}},
                new int[][]{{0, 2}, {1, 3}},
                new int[][]{{0, 3}, {1, 2}});
    }

    /** 落库赛制由实际性别构成判定：每队一男一女=混双，全男=男双，全女=女双 */
    private int formatOf(List<Long> teamA, List<Long> teamB, int baseFormat,
                         Map<Long, RotationPlayer> byId) {
        if (baseFormat == Constants.FORMAT_SINGLES) {
            return Constants.FORMAT_SINGLES;
        }
        int maleA = malesOf(teamA, byId);
        int maleB = malesOf(teamB, byId);
        if (teamA.size() == 2 && teamB.size() == 2 && maleA == 1 && maleB == 1) {
            return Constants.FORMAT_MIXED_DOUBLES;
        }
        if (maleA == teamA.size() && maleB == teamB.size()) {
            return Constants.FORMAT_MEN_DOUBLES;
        }
        if (maleA == 0 && maleB == 0) {
            return Constants.FORMAT_WOMEN_DOUBLES;
        }
        // 双方构成不一致（如男双 vs 混双）：按 A 队构成定
        return maleA == teamA.size() ? Constants.FORMAT_MEN_DOUBLES
                : (maleA == 0 ? Constants.FORMAT_WOMEN_DOUBLES : Constants.FORMAT_MIXED_DOUBLES);
    }

    private int malesOf(List<Long> ids, Map<Long, RotationPlayer> byId) {
        int n = 0;
        for (Long id : ids) {
            RotationPlayer p = byId.get(id);
            if (p != null && p.gender() == Constants.GENDER_MALE) {
                n++;
            }
        }
        return n;
    }

    /** A 队期望胜率：与 EloCalculator.settle 用的是同一个公式（队伍平均分 → E） */
    private double expectedOf(List<Long> teamA, List<Long> teamB, Map<Long, RotationPlayer> byId) {
        return eloCalculator.expected(avgRating(teamA, byId), avgRating(teamB, byId));
    }

    private double avgRating(List<Long> ids, Map<Long, RotationPlayer> byId) {
        double sum = 0;
        int n = 0;
        for (Long id : ids) {
            RotationPlayer p = byId.get(id);
            if (p != null) {
                sum += p.rating();
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** 代价：重复搭档 + 重复对手 + ELO 期望偏离 50%，越小越好 */
    private double cost(List<CourtMatch> matches, Map<String, Integer> partnerHistory,
                        Map<String, Integer> opponentHistory, boolean balanceStrength) {
        double total = 0;
        for (CourtMatch m : matches) {
            total += W_PARTNER * sumHistory(partnerHistory, pairsOf(m.teamA()));
            total += W_PARTNER * sumHistory(partnerHistory, pairsOf(m.teamB()));
            for (Long a : m.teamA()) {
                for (Long b : m.teamB()) {
                    total += W_OPPONENT * opponentHistory.getOrDefault(key(a, b), 0);
                }
            }
            if (balanceStrength) {
                total += W_IMBALANCE * Math.abs(m.expectedA() - 0.5);
            }
        }
        return total;
    }

    private List<String> pairsOf(List<Long> team) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < team.size(); i++) {
            for (int j = i + 1; j < team.size(); j++) {
                keys.add(key(team.get(i), team.get(j)));
            }
        }
        return keys;
    }

    private double sumHistory(Map<String, Integer> history, List<String> keys) {
        int sum = 0;
        for (String k : keys) {
            sum += history.getOrDefault(k, 0);
        }
        return sum;
    }

    private void recordPairs(Map<String, Integer> history, List<Long> team) {
        for (int i = 0; i < team.size(); i++) {
            for (int j = i + 1; j < team.size(); j++) {
                history.merge(key(team.get(i), team.get(j)), 1, Integer::sum);
            }
        }
    }

    private String key(Long a, Long b) {
        return a < b ? a + "_" + b : b + "_" + a;
    }

    /* ---------------- 统计 ---------------- */

    private List<PlayerStat> buildStats(List<RotationPlayer> players, Map<Long, Integer> playCount,
                                        Map<Long, Integer> restCount, Map<String, Integer> partnerHistory) {
        return players.stream()
                .map(p -> new PlayerStat(
                        p.userId(),
                        p.label(),
                        p.gender(),
                        p.rating(),
                        playCount.getOrDefault(p.userId(), 0),
                        restCount.getOrDefault(p.userId(), 0),
                        partnersOf(p.userId(), players, partnerHistory)))
                .toList();
    }

    private List<PartnerCount> partnersOf(Long userId, List<RotationPlayer> players,
                                          Map<String, Integer> partnerHistory) {
        return players.stream()
                .map(p -> new PartnerCount(p.userId(),
                        partnerHistory.getOrDefault(key(userId, p.userId()), 0)))
                .filter(pc -> pc.times() > 0 && !pc.userId().equals(userId))
                .sorted(Comparator.comparingInt(PartnerCount::times).reversed())
                .toList();
    }
}
