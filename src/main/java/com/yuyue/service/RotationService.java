package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.config.EloProperties;
import com.yuyue.dto.RotationRequest;
import com.yuyue.dto.RotationResponse;
import com.yuyue.engine.EloCalculator;
import com.yuyue.engine.RotationEngine;
import com.yuyue.entity.Game;
import com.yuyue.entity.MatchGame;
import com.yuyue.entity.MatchScoreLog;
import com.yuyue.entity.RatingHistory;
import com.yuyue.entity.Registration;
import com.yuyue.entity.User;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.GameMapper;
import com.yuyue.util.MatchFormatUtil;
import com.yuyue.mapper.MatchGameMapper;
import com.yuyue.mapper.MatchScoreLogMapper;
import com.yuyue.mapper.RatingHistoryMapper;
import com.yuyue.mapper.RegistrationMapper;
import com.yuyue.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 轮排：多人、少场地时按轮安排上场与休息，保证机会均等、实力均衡。
 * <p>
 * /rotation/plan 是纯试算；/rotation/game/{id} 直接读球局报名名单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RotationService {

    private final RotationEngine rotationEngine;
    private final EloCalculator eloCalculator;
    private final GameMapper gameMapper;
    private final RegistrationMapper registrationMapper;
    private final UserMapper userMapper;
    private final MatchGameMapper matchGameMapper;
    private final MatchScoreLogMapper matchScoreLogMapper;
    private final RatingHistoryMapper ratingHistoryMapper;
    private final EloProperties eloProperties;

    /** 试算：直接用传入的名单 */
    public RotationResponse plan(RotationRequest req) {
        List<RotationEngine.RotationPlayer> players = new ArrayList<>();
        for (int i = 0; i < req.getPlayers().size(); i++) {
            RotationRequest.RotationPlayer p = req.getPlayers().get(i);
            long userId = p.getUserId() == null ? i + 1L : p.getUserId();
            players.add(new RotationEngine.RotationPlayer(
                    userId,
                    label(p.getLabel(), userId),
                    p.getGender() == null ? 0 : p.getGender(),
                    p.getRating() == null ? eloProperties.getDefaultRating() : p.getRating()));
        }
        return build(null, players, req, Map.of());
    }

    /** 真实球局：读报名名单（对外匿名），并把轮排结果落库为可计分的对阵。仅发起人可生成 */
    @Transactional
    public RotationResponse planForGame(Long operatorId, Long gameId, RotationRequest req) {
        Game game = gameMapper.selectById(gameId);
        if (game == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "球局不存在");
        }
        // 轮排表会覆盖已有安排并置球局为已编排，只能由发起人或管理员操作
        if (!isAdmin(operatorId) && !java.util.Objects.equals(game.getCreatorId(), operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有发起人或管理员可以生成轮排表");
        }
        List<Registration> regs = registrationMapper.selectList(
                new LambdaQueryWrapper<Registration>().eq(Registration::getGameId, gameId));
        if (regs.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该球局还没有人报名");
        }
        Map<Long, User> userMap = loadUserMap(regs);
        // 轮排表使用真实姓名 + 个人头像，不再使用匿名昵称
        Map<Long, String> avatars = userMap.values().stream()
                .filter(u -> u.getAvatar() != null)
                .collect(Collectors.toMap(User::getId, User::getAvatar, (a, b) -> a));
        List<RotationEngine.RotationPlayer> players = regs.stream()
                .map(r -> {
                    User u = userMap.get(r.getUserId());
                    return new RotationEngine.RotationPlayer(
                            r.getUserId(),
                            realLabel(u),
                            r.getGender(),
                            r.getRating());
                })
                .toList();
        validateGenderForRule(players, req);
        RotationResponse resp = build(gameId, players, req, avatars);

        // 轮排表就是现场执行计划：生成后直接置为已编排
        if (game.getStatus() == null || game.getStatus() < Constants.GAME_STATUS_ARRANGED) {
            game.setStatus(Constants.GAME_STATUS_ARRANGED);
            gameMapper.updateById(game);
        }
        return resp;
    }

    /**
     * 读取该球局已保存的轮排表：重新进入轮排页面时用它还原（含已录比分）。
     * 该球局还没生成过轮排时返回 null。
     */
    public RotationResponse getRotationForGame(Long gameId) {
        Game game = gameMapper.selectById(gameId);
        if (game == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "球局不存在");
        }
        List<MatchGame> saved = matchGameMapper.selectList(new LambdaQueryWrapper<MatchGame>()
                .eq(MatchGame::getGameId, gameId)
                .gt(MatchGame::getRoundNo, 0)
                .orderByAsc(MatchGame::getRoundNo, MatchGame::getCourt));
        if (saved.isEmpty()) {
            return null;
        }

        List<Registration> regs = registrationMapper.selectList(
                new LambdaQueryWrapper<Registration>().eq(Registration::getGameId, gameId));

        // 读取最新用户资料：总 ELO 已被结算消费者更新，展示时要反映最新积分
        List<Long> userIds = regs.stream().map(Registration::getUserId).distinct().toList();
        Map<Long, User> users = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        // 轮排表使用真实姓名 + 个人头像
        Map<Long, String> avatars = users.values().stream()
                .filter(u -> u.getAvatar() != null)
                .collect(Collectors.toMap(User::getId, User::getAvatar, (a, b) -> a));

        List<RotationEngine.RotationPlayer> players = regs.stream()
                .map(r -> {
                    User u = users.get(r.getUserId());
                    return new RotationEngine.RotationPlayer(
                            r.getUserId(),
                            realLabel(u),
                            r.getGender(),
                            u == null ? r.getRating() : u.getRating());
                })
                .toList();
        Map<Long, RotationEngine.RotationPlayer> byId = new LinkedHashMap<>();
        players.forEach(p -> byId.put(p.userId(), p));

        // 按轮次分组，还原每片场地的对阵与本轮休息的人
        Map<Integer, List<MatchGame>> byRound = new LinkedHashMap<>();
        for (MatchGame mg : saved) {
            byRound.computeIfAbsent(mg.getRoundNo(), k -> new ArrayList<>()).add(mg);
        }

        // 计分留痕：每场的裁判（首次计分人）与改分历史，轮排页据此展示与核对比分
        Map<Long, List<RotationResponse.ScoreLogItem>> scoreLogsByMatch = loadScoreLogs(saved);
        // 两队的性别构成：两队可能不同（男双 vs 混双），分别标注避免显示成「混双打混双」
        Map<Long, Integer> genders = new LinkedHashMap<>();
        byId.forEach((id, p) -> genders.put(id, p.gender()));

        Map<Long, Integer> playCount = new LinkedHashMap<>();
        players.forEach(p -> playCount.put(p.userId(), 0));
        Map<String, Integer> partnerHistory = new LinkedHashMap<>();

        List<RotationResponse.RoundItem> rounds = new ArrayList<>();
        int courts = 0;
        int format = saved.get(0).getFormat();
        for (Map.Entry<Integer, List<MatchGame>> entry : byRound.entrySet()) {
            List<RotationResponse.MatchItem> items = new ArrayList<>();
            Set<Long> onCourt = new LinkedHashSet<>();
            for (MatchGame mg : entry.getValue()) {
                List<Long> a = split(mg.getTeamA());
                List<Long> b = split(mg.getTeamB());
                onCourt.addAll(a);
                onCourt.addAll(b);
                recordPairs(partnerHistory, a);
                recordPairs(partnerHistory, b);
                courts = Math.max(courts, mg.getCourt());
                List<RotationResponse.ScoreLogItem> logs =
                        scoreLogsByMatch.getOrDefault(mg.getId(), List.of());
                RotationResponse.ScoreLogItem referee = logs.stream()
                        .filter(l -> Objects.equals(l.getAction(), Constants.SCORE_ACTION_FIRST))
                        .findFirst()
                        .orElse(null);
                items.add(RotationResponse.MatchItem.builder()
                        .matchId(mg.getId())
                        .court(mg.getCourt())
                        .format(mg.getFormat())
                        .formatName(formatName(mg.getFormat()))
                        .teamAFormatName(MatchFormatUtil.sideFormatName(a, genders))
                        .teamBFormatName(MatchFormatUtil.sideFormatName(b, genders))
                        .teamA(briefs(a, byId, avatars))
                        .teamB(briefs(b, byId, avatars))
                        .sumA(sum(a, byId))
                        .sumB(sum(b, byId))
                        .expectedA(expectedOf(a, b, byId))
                        .scoreA(mg.getScoreA() == null ? 0 : mg.getScoreA())
                        .scoreB(mg.getScoreB() == null ? 0 : mg.getScoreB())
                        .winner(mg.getWinner() == null ? 0 : mg.getWinner())
                        .refereeId(referee != null ? referee.getOperatorId() : null)
                        .refereeName(referee != null ? referee.getOperatorName() : null)
                        .scoreLogs(logs)
                        .build());
            }
            onCourt.forEach(id -> playCount.merge(id, 1, Integer::sum));
            List<Long> resting = players.stream()
                    .map(RotationEngine.RotationPlayer::userId)
                    .filter(id -> !onCourt.contains(id))
                    .toList();
            rounds.add(RotationResponse.RoundItem.builder()
                    .round(entry.getKey())
                    .matches(items)
                    .resting(briefs(resting, byId, avatars))
                    .build());
        }

        int totalRounds = byRound.size();

        // 统计已录分比赛的胜负与局内得分
        Map<Long, int[]> scoreStat = new LinkedHashMap<>();
        for (MatchGame mg : saved) {
            if (mg.getWinner() == null || mg.getWinner() == 0) {
                continue;
            }
            List<Long> a = split(mg.getTeamA());
            List<Long> b = split(mg.getTeamB());
            int scoreA = mg.getScoreA() == null ? 0 : mg.getScoreA();
            int scoreB = mg.getScoreB() == null ? 0 : mg.getScoreB();
            int winner = mg.getWinner();
            int netA = scoreA - scoreB;
            int netB = scoreB - scoreA;
            for (Long id : a) {
                int[] st = scoreStat.computeIfAbsent(id, k -> new int[3]);
                st[2] += netA;
                if (winner == 1) st[0]++;
                else if (winner == 2) st[1]++;
            }
            for (Long id : b) {
                int[] st = scoreStat.computeIfAbsent(id, k -> new int[3]);
                st[2] += netB;
                if (winner == 2) st[0]++;
                else if (winner == 1) st[1]++;
            }
        }

        // 统计本球局每人的 ELO 变化（结算时写入的 rating_history 流水）
        Map<Long, Integer> eloDelta = new LinkedHashMap<>();
        List<Long> matchIds = saved.stream().map(MatchGame::getId).toList();
        if (!matchIds.isEmpty()) {
            List<RatingHistory> histories = ratingHistoryMapper.selectList(
                    new LambdaQueryWrapper<RatingHistory>().in(RatingHistory::getMatchId, matchIds));
            for (RatingHistory h : histories) {
                if (h.getUserId() == null || h.getDelta() == null) {
                    continue;
                }
                eloDelta.merge(h.getUserId(), h.getDelta(), Integer::sum);
            }
        }

        List<RotationResponse.StatItem> stats = players.stream()
                .map(p -> {
                    int play = playCount.getOrDefault(p.userId(), 0);
                    int[] st = scoreStat.getOrDefault(p.userId(), new int[3]);
                    List<RotationResponse.PartnerItem> partners = players.stream()
                            .map(o -> RotationResponse.PartnerItem.builder()
                                    .userId(o.userId())
                                    .label(o.label())
                                    .times(partnerHistory.getOrDefault(keyOf(p.userId(), o.userId()), 0))
                                    .build())
                            .filter(pc -> pc.getTimes() > 0 && !pc.getUserId().equals(p.userId()))
                            .sorted(Comparator.comparingInt(RotationResponse.PartnerItem::getTimes).reversed())
                            .toList();
                    return RotationResponse.StatItem.builder()
                            .userId(p.userId())
                            .label(p.label())
                            .gender(p.gender())
                            .rating(p.rating())
                            .avatar(avatars.get(p.userId()))
                            .playCount(play)
                            .restCount(Math.max(totalRounds - play, 0))
                            .winCount(st[0])
                            .loseCount(st[1])
                            .points(st[2])
                            .eloDelta(eloDelta.getOrDefault(p.userId(), 0))
                            .partners(partners)
                            .build();
                })
                .toList();

        return RotationResponse.builder()
                .courts(courts)
                .rounds(totalRounds)
                .format(format)
                .formatName(format == Constants.FORMAT_SINGLES ? "单打" : "双打")
                .totalPlayers(players.size())
                .maleCount(countGender(players, Constants.GENDER_MALE))
                .femaleCount(countGender(players, Constants.GENDER_FEMALE))
                .players(briefs(players, avatars))
                .rotation(rounds)
                .stats(stats)
                .build();
    }

    /**
     * 批量加载每场对阵的计分日志（按提交顺序升序）。
     * 日志操作人未必在报名名单里（如发起人代录），故单独取用户表并用昵称优先的展示名。
     */
    private Map<Long, List<RotationResponse.ScoreLogItem>> loadScoreLogs(List<MatchGame> saved) {
        if (saved.isEmpty()) {
            return Map.of();
        }
        List<Long> matchIds = saved.stream().map(MatchGame::getId).toList();
        List<MatchScoreLog> logs = matchScoreLogMapper.selectList(
                new LambdaQueryWrapper<MatchScoreLog>()
                        .in(MatchScoreLog::getMatchId, matchIds)
                        .orderByAsc(MatchScoreLog::getId));
        if (logs.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> operatorLabels = new LinkedHashMap<>();
        Map<Long, String> operatorAvatars = new LinkedHashMap<>();
        for (Long opId : logs.stream().map(MatchScoreLog::getOperatorId).distinct().toList()) {
            User u = userMapper.selectById(opId);
            String label = null;
            if (u != null) {
                if (u.getNickname() != null && !u.getNickname().isBlank()) {
                    label = u.getNickname().trim();
                } else if (u.getName() != null && !u.getName().isBlank()) {
                    label = u.getName().trim();
                }
                operatorAvatars.put(opId, u.getAvatar());
            }
            operatorLabels.put(opId, label != null ? label : "球友" + opId);
        }
        Map<Long, List<RotationResponse.ScoreLogItem>> out = new LinkedHashMap<>();
        for (MatchScoreLog l : logs) {
            out.computeIfAbsent(l.getMatchId(), k -> new ArrayList<>())
                    .add(RotationResponse.ScoreLogItem.builder()
                            .operatorId(l.getOperatorId())
                            .operatorName(operatorLabels.getOrDefault(l.getOperatorId(), "球友" + l.getOperatorId()))
                            .operatorAvatar(operatorAvatars.get(l.getOperatorId()))
                            .action(l.getAction())
                            .scoreA(l.getScoreA())
                            .scoreB(l.getScoreB())
                            .prevScoreA(l.getPrevScoreA())
                            .prevScoreB(l.getPrevScoreB())
                            .time(l.getCreateTime())
                            .build());
        }
        return out;
    }

    /** 批量取报名人用户信息 */
    private Map<Long, User> loadUserMap(List<Registration> regs) {
        if (regs.isEmpty()) {
            return Map.of();
        }
        List<Long> userIds = regs.stream().map(Registration::getUserId).distinct().toList();
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    /** 轮排表展示名：优先真实姓名，没有时按 userId 兜底 */
    private String realLabel(User u) {
        if (u != null && u.getName() != null && !u.getName().isBlank()) {
            return u.getName().trim();
        }
        return "球友" + (u != null ? u.getId() : 0);
    }

    private RotationResponse build(Long gameId, List<RotationEngine.RotationPlayer> players,
                                    RotationRequest req, Map<Long, String> avatarByUser) {
        boolean balance = !Boolean.FALSE.equals(req.getBalanceStrength());
        int genderRule = req.getGenderRule() == null ? Constants.GENDER_RULE_ANY : req.getGenderRule();
        RotationEngine.RotationPlan result = rotationEngine.plan(
                players, req.getCourts(), req.getRounds(), req.getFormat(), balance, genderRule);

        Map<Long, RotationEngine.RotationPlayer> byId = new LinkedHashMap<>();
        players.forEach(p -> byId.put(p.userId(), p));
        Map<Long, Integer> genders = new LinkedHashMap<>();
        byId.forEach((id, p) -> genders.put(id, p.gender()));

        List<RotationResponse.PlayerBrief> briefs = briefs(players, avatarByUser);

        // 真实球局：把轮排结果持久化为 match_game，点击场地就能计分。
        // 已有比分的对阵不删（保留战绩），只清掉还没打的旧对阵，然后生成新的上场安排。
        if (gameId != null) {
            matchGameMapper.delete(new LambdaQueryWrapper<MatchGame>()
                    .eq(MatchGame::getGameId, gameId)
                    .eq(MatchGame::getWinner, 0));
        }

        List<RotationResponse.RoundItem> rounds = new ArrayList<>();
        for (RotationEngine.RotationRound r : result.rotation()) {
            List<RotationResponse.MatchItem> matchItems = new ArrayList<>();
            for (RotationEngine.CourtMatch m : r.matches()) {
                Long matchId = null;
                if (gameId != null) {
                    MatchGame mg = new MatchGame();
                    mg.setGameId(gameId);
                    mg.setFormat(m.format());
                    mg.setRoundNo(r.round());
                    mg.setCourt(m.court());
                    mg.setTeamA(join(m.teamA()));
                    mg.setTeamB(join(m.teamB()));
                    mg.setScoreA(0);
                    mg.setScoreB(0);
                    mg.setWinner(0);
                    mg.setSettleStatus(Constants.SETTLE_PENDING);
                    matchGameMapper.insert(mg);
                    matchId = mg.getId();
                }
                matchItems.add(RotationResponse.MatchItem.builder()
                        .matchId(matchId)
                        .court(m.court())
                        .format(m.format())
                        .formatName(formatName(m.format()))
                        .teamAFormatName(MatchFormatUtil.sideFormatName(m.teamA(), genders))
                        .teamBFormatName(MatchFormatUtil.sideFormatName(m.teamB(), genders))
                        .teamA(briefs(m.teamA(), byId, avatarByUser))
                        .teamB(briefs(m.teamB(), byId, avatarByUser))
                        .sumA(sum(m.teamA(), byId))
                        .sumB(sum(m.teamB(), byId))
                        .expectedA(m.expectedA())
                        .scoreA(0)
                        .scoreB(0)
                        .winner(0)
                        .build());
            }
            rounds.add(RotationResponse.RoundItem.builder()
                    .round(r.round())
                    .matches(matchItems)
                    .resting(briefs(r.resting(), byId, avatarByUser))
                    .build());
        }

        List<RotationResponse.StatItem> stats = result.stats().stream()
                .map(s -> RotationResponse.StatItem.builder()
                        .userId(s.userId())
                        .label(s.label())
                        .gender(s.gender())
                        .rating(s.rating())
                        .avatar(avatarByUser.get(s.userId()))
                        .playCount(s.playCount())
                        .restCount(s.restCount())
                        .winCount(0)
                        .loseCount(0)
                        .points(0)
                        .eloDelta(0)
                        .partners(s.partners().stream()
                                .map(pc -> {
                                    RotationEngine.RotationPlayer who = byId.get(pc.userId());
                                    return RotationResponse.PartnerItem.builder()
                                            .userId(pc.userId())
                                            .label(who == null ? String.valueOf(pc.userId()) : who.label())
                                            .times(pc.times())
                                            .build();
                                })
                                .toList())
                        .build())
                .toList();

        return RotationResponse.builder()
                .courts(result.courts())
                .rounds(result.rounds())
                .format(result.format())
                .formatName(result.format() == Constants.FORMAT_SINGLES ? "单打" : "双打")
                .totalPlayers(players.size())
                .maleCount(countGender(players, Constants.GENDER_MALE))
                .femaleCount(countGender(players, Constants.GENDER_FEMALE))
                .players(briefs)
                .rotation(rounds)
                .stats(stats)
                .build();
    }

    private List<RotationResponse.PlayerBrief> briefs(List<RotationEngine.RotationPlayer> players,
                                                      Map<Long, String> avatarByUser) {
        return players.stream()
                .map(p -> RotationResponse.PlayerBrief.builder()
                        .userId(p.userId())
                        .label(p.label())
                        .gender(p.gender())
                        .rating(p.rating())
                        .avatar(avatarByUser.get(p.userId()))
                        .build())
                .toList();
    }

    private List<RotationResponse.PlayerBrief> briefs(List<Long> ids,
                                                      Map<Long, RotationEngine.RotationPlayer> byId,
                                                      Map<Long, String> avatarByUser) {
        List<RotationResponse.PlayerBrief> list = new ArrayList<>();
        for (Long id : ids) {
            RotationEngine.RotationPlayer p = byId.get(id);
            if (p != null) {
                list.add(RotationResponse.PlayerBrief.builder()
                        .userId(id).label(p.label()).gender(p.gender()).rating(p.rating())
                        .avatar(avatarByUser.get(id))
                        .build());
            }
        }
        return list;
    }

    private int sum(List<Long> ids, Map<Long, RotationEngine.RotationPlayer> byId) {
        int total = 0;
        for (Long id : ids) {
            RotationEngine.RotationPlayer p = byId.get(id);
            if (p != null) {
                total += p.rating();
            }
        }
        return total;
    }

    /** 用真实 ELO 公式算 A 队期望胜率（队伍平均分 → E），与打完结算时一致 */
    private double expectedOf(List<Long> teamA, List<Long> teamB,
                              Map<Long, RotationEngine.RotationPlayer> byId) {
        return eloCalculator.expected(avg(teamA, byId), avg(teamB, byId));
    }

    private double avg(List<Long> ids, Map<Long, RotationEngine.RotationPlayer> byId) {
        double sum = 0;
        int n = 0;
        for (Long id : ids) {
            RotationEngine.RotationPlayer p = byId.get(id);
            if (p != null) {
                sum += p.rating();
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private String formatName(Integer format) {
        if (format == null) {
            return "未知";
        }
        return switch (format) {
            case Constants.FORMAT_SINGLES -> "单打";
            case Constants.FORMAT_MEN_DOUBLES -> "男双";
            case Constants.FORMAT_WOMEN_DOUBLES -> "女双";
            case Constants.FORMAT_MIXED_DOUBLES -> "混双";
            default -> "双打";
        };
    }

    private String label(String input, long fallbackId) {
        if (input != null && !input.isBlank()) {
            return input.trim();
        }
        return "球员" + fallbackId;
    }

    private static String join(Iterable<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    private static List<Long> split(String ids) {
        List<Long> list = new ArrayList<>();
        if (ids == null || ids.isBlank()) {
            return list;
        }
        for (String s : ids.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                list.add(Long.valueOf(t));
            }
        }
        return list;
    }

    private static String keyOf(Long a, Long b) {
        return a < b ? a + "_" + b : b + "_" + a;
    }

    private static void recordPairs(Map<String, Integer> history, List<Long> team) {
        for (int i = 0; i < team.size(); i++) {
            for (int j = i + 1; j < team.size(); j++) {
                history.merge(keyOf(team.get(i), team.get(j)), 1, Integer::sum);
            }
        }
    }

    /** 判断 operatorId 对应用户是否管理员（user.isAdmin == 1） */
    private boolean isAdmin(Long operatorId) {
        User u = userMapper.selectById(operatorId);
        return u != null && u.getIsAdmin() != null && u.getIsAdmin() == 1;
    }

    private int countGender(List<RotationEngine.RotationPlayer> players, int gender) {
        int n = 0;
        for (RotationEngine.RotationPlayer p : players) {
            if (p.gender() == gender) {
                n++;
            }
        }
        return n;
    }

    /**
     * 使用按性别规则编排前，先检查报名者中男女数量是否足够；
     * 如果男/女都是 0，说明报名者还没填性别，给出更明确的提示。
     */
    private void validateGenderForRule(List<RotationEngine.RotationPlayer> players, RotationRequest req) {
        Integer format = req.getFormat();
        int genderRule = req.getGenderRule() == null ? Constants.GENDER_RULE_ANY : req.getGenderRule();
        if (format == null || format == Constants.FORMAT_SINGLES || genderRule == Constants.GENDER_RULE_ANY) {
            return;
        }
        int males = countGender(players, Constants.GENDER_MALE);
        int females = countGender(players, Constants.GENDER_FEMALE);
        if (genderRule == Constants.GENDER_RULE_MIXED) {
            if (males < 2 || females < 2) {
                String msg = (males == 0 && females == 0)
                        ? "参数错误：报名者尚未填写性别，请先让报名者完善性别后再使用混双规则"
                        : "参数错误：混双每场需要 2 男 2 女，当前男 " + males + " 人、女 " + females + " 人，无法开赛";
                throw new BizException(ErrorCode.PARAM_ERROR, msg);
            }
        } else if (genderRule == Constants.GENDER_RULE_MEN) {
            if (males < 4) {
                String msg = males == 0
                        ? "参数错误：报名者尚未填写性别，请先让报名者完善性别后再使用男双规则"
                        : "参数错误：男双每场需要 4 名男生，当前男生 " + males + " 人，无法开赛";
                throw new BizException(ErrorCode.PARAM_ERROR, msg);
            }
        } else if (genderRule == Constants.GENDER_RULE_WOMEN) {
            if (females < 4) {
                String msg = females == 0
                        ? "参数错误：报名者尚未填写性别，请先让报名者完善性别后再使用女双规则"
                        : "参数错误：女双每场需要 4 名女生，当前女生 " + females + " 人，无法开赛";
                throw new BizException(ErrorCode.PARAM_ERROR, msg);
            }
        }
    }
}
