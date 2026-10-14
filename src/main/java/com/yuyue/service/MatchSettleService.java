package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.Constants;
import com.yuyue.engine.EloCalculator;
import com.yuyue.engine.EloCalculator.PlayerInput;
import com.yuyue.entity.MatchGame;
import com.yuyue.entity.RatingHistory;
import com.yuyue.entity.User;
import com.yuyue.mapper.MatchGameMapper;
import com.yuyue.mapper.RatingHistoryMapper;
import com.yuyue.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对局结算：算 ELO 并落库积分变更、写评级历史、同步 Redis 榜单。
 *
 * <p>幂等：对局已结算（settle_status=1）时直接跳过。
 *
 * <p>上报对局与现场计分接口均在同一事务内同步调用本类，积分立即落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchSettleService {

    private final UserMapper userMapper;
    private final RatingHistoryMapper ratingHistoryMapper;
    private final MatchGameMapper matchGameMapper;
    private final EloCalculator eloCalculator;
    private final RankingService rankingService;

    @Transactional
    public void settle(MatchGame match, Integer winner) {
        if (match == null || match.getId() == null) {
            return;
        }
        if (match.getSettleStatus() != null && match.getSettleStatus() == Constants.SETTLE_DONE) {
            log.info("对局 {} 已结算，跳过", match.getId());
            return;
        }
        if (winner == null || (winner != Constants.WINNER_A && winner != Constants.WINNER_B)) {
            log.warn("对局 {} 胜方非法（winner={}），放弃结算", match.getId(), winner);
            return;
        }

        List<Long> idsA = split(match.getTeamA());
        List<Long> idsB = split(match.getTeamB());
        List<PlayerInput> teamA = loadTeam(idsA);
        List<PlayerInput> teamB = loadTeam(idsB);
        if (teamA.size() != idsA.size() || teamB.size() != idsB.size()) {
            log.error("对局 {} 有成员不存在，放弃结算", match.getId());
            return;
        }

        EloCalculator.TeamResult[] results = eloCalculator.settle(teamA, teamB, winner,
                match.getScoreA() == null ? 0 : match.getScoreA(),
                match.getScoreB() == null ? 0 : match.getScoreB());
        apply(results[0], match.getId(), winner == Constants.WINNER_A);
        apply(results[1], match.getId(), winner == Constants.WINNER_B);

        match.setSettleStatus(Constants.SETTLE_DONE);
        match.setSettleTime(LocalDateTime.now());
        matchGameMapper.updateById(match);

        log.info("对局 {} 结算完成: A队期望={}(性别修正{}), B队期望={}(性别修正{})",
                match.getId(),
                String.format("%.3f", results[0].expected()), String.format("%.1f", results[0].genderAdjust()),
                String.format("%.3f", results[1].expected()), String.format("%.1f", results[1].genderAdjust()));
    }

    /**
     * 撤销已结算的对局：按本场的 rating_history 流水原路退回积分与胜负场次，
     * 再删除流水、同步 Redis 榜单。用于「录成 0:0 撤销本场」的误点补救。
     *
     * <p>幂等：没有流水可退时直接返回（不会重复扣分）。
     */
    @Transactional
    public void rollback(MatchGame match) {
        if (match == null || match.getId() == null) {
            return;
        }
        List<RatingHistory> histories = ratingHistoryMapper.selectList(
                new LambdaQueryWrapper<RatingHistory>()
                        .eq(RatingHistory::getMatchId, match.getId())
                        .orderByAsc(RatingHistory::getId));
        if (histories.isEmpty()) {
            log.info("对局 {} 没有结算流水，无需回退", match.getId());
            return;
        }
        List<Long> idsA = split(match.getTeamA());
        List<Long> idsB = split(match.getTeamB());
        for (RatingHistory h : histories) {
            User user = userMapper.selectById(h.getUserId());
            if (user == null) {
                continue;
            }
            // 该用户在本场是胜方还是负方：按当前 winner 与所属队伍判定（winner 尚未被清零）
            boolean inA = idsA.contains(h.getUserId());
            boolean inB = idsB.contains(h.getUserId());
            boolean won = (inA && match.getWinner() != null && match.getWinner() == Constants.WINNER_A)
                    || (inB && match.getWinner() != null && match.getWinner() == Constants.WINNER_B);
            user.setRating(h.getRatingBefore());
            user.setGamesPlayed(Math.max(0, user.getGamesPlayed() - 1));
            if (won) {
                user.setWinCount(Math.max(0, user.getWinCount() - 1));
            } else if (inA || inB) {
                user.setLossCount(Math.max(0, user.getLossCount() - 1));
            }
            userMapper.updateById(user);
            rankingService.updateScore(user.getId(), user.getRating());
            ratingHistoryMapper.deleteById(h.getId());

            log.info("积分回退: user={} {} -> {} (撤销对局 {})",
                    h.getUserId(), h.getRatingAfter(), h.getRatingBefore(), match.getId());
        }
    }

    private void apply(EloCalculator.TeamResult team, Long matchId, boolean won) {
        for (EloCalculator.PlayerResult r : team.players()) {
            User user = userMapper.selectById(r.userId());
            if (user == null) {
                continue;
            }
            user.setRating(r.ratingAfter());
            user.setGamesPlayed(user.getGamesPlayed() + 1);
            if (won) {
                user.setWinCount(user.getWinCount() + 1);
            } else {
                user.setLossCount(user.getLossCount() + 1);
            }
            userMapper.updateById(user);

            RatingHistory history = new RatingHistory();
            history.setUserId(r.userId());
            history.setMatchId(matchId);
            history.setRatingBefore(r.ratingBefore());
            history.setRatingAfter(r.ratingAfter());
            history.setDelta(r.delta());
            history.setKFactor(r.kFactor());
            history.setExpected(BigDecimal.valueOf(r.expected()).setScale(4, RoundingMode.HALF_UP));
            history.setMarginDelta(r.marginDelta());
            history.setGenderAdjust(BigDecimal.valueOf(team.genderAdjust()).setScale(2, RoundingMode.HALF_UP));
            ratingHistoryMapper.insert(history);

            // 同步 Redis 积分榜
            rankingService.updateScore(r.userId(), r.ratingAfter());

            log.info("积分更新: user={} {} -> {} (基础{}{}, 分差加成{}{}, K={}, E={})",
                    r.userId(), r.ratingBefore(), r.ratingAfter(),
                    r.delta() - r.marginDelta() >= 0 ? "+" : "", r.delta() - r.marginDelta(),
                    r.marginDelta() >= 0 ? "+" : "", r.marginDelta(), r.kFactor(),
                    String.format("%.3f", r.expected()));
        }
    }

    private List<PlayerInput> loadTeam(List<Long> userIds) {
        return userIds.stream()
                .map(id -> {
                    User u = userMapper.selectById(id);
                    if (u == null) {
                        return null;
                    }
                    // gender 供 ELO 性别修正使用（男双打女双/混双时压低女方期望）
                    int gender = u.getGender() == null ? Constants.GENDER_UNKNOWN : u.getGender();
                    return new PlayerInput(u.getId(), u.getRating(), u.getGamesPlayed(), gender);
                })
                .filter(p -> p != null)
                .toList();
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
}
