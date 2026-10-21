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
import com.yuyue.util.MatchFormatUtil;
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
 * <p>按 match_game.format 分类别结算：1单打→单打，2男双/3女双→双打，4混双→混双。
 * 三类各有独立积分与胜负场；全局 rating/gamesPlayed/winCount/lossCount 照旧累加，
 * 其中全局 rating 维护为三类分均值（综合展示用）。
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

        int category = MatchFormatUtil.categoryOfFormat(
                match.getFormat() == null ? Constants.FORMAT_SINGLES : match.getFormat());
        List<Long> idsA = split(match.getTeamA());
        List<Long> idsB = split(match.getTeamB());
        List<PlayerInput> teamA = loadTeam(idsA, category);
        List<PlayerInput> teamB = loadTeam(idsB, category);
        if (teamA.size() != idsA.size() || teamB.size() != idsB.size()) {
            log.error("对局 {} 有成员不存在，放弃结算", match.getId());
            return;
        }

        EloCalculator.TeamResult[] results = eloCalculator.settle(teamA, teamB, winner,
                match.getScoreA() == null ? 0 : match.getScoreA(),
                match.getScoreB() == null ? 0 : match.getScoreB());
        apply(results[0], match.getId(), winner == Constants.WINNER_A, category);
        apply(results[1], match.getId(), winner == Constants.WINNER_B, category);

        match.setSettleStatus(Constants.SETTLE_DONE);
        match.setSettleTime(LocalDateTime.now());
        matchGameMapper.updateById(match);

        log.info("对局 {} 结算完成({}): A队期望={}(性别修正{}), B队期望={}(性别修正{})",
                match.getId(), MatchFormatUtil.categoryName(category),
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
            int category = h.getCategory() == null ? Constants.CATEGORY_SINGLES : h.getCategory();
            // 分类积分与分类胜负场原路退回（旧流水的 ratingBefore 是综合时代的值，
            // V30 回填 category 后退回对应类别，属可接受的一次性偏差）
            setCategoryRating(user, category, h.getRatingBefore());
            decCategoryResult(user, category, won, inA || inB);
            user.setGamesPlayed(Math.max(0, nullSafe(user.getGamesPlayed()) - 1));
            if (won) {
                user.setWinCount(Math.max(0, nullSafe(user.getWinCount()) - 1));
            } else if (inA || inB) {
                user.setLossCount(Math.max(0, nullSafe(user.getLossCount()) - 1));
            }
            refreshOverallRating(user);
            userMapper.updateById(user);
            rankingService.updateScore(user.getId(), nullSafe(categoryRating(user, category)), category);
            ratingHistoryMapper.deleteById(h.getId());

            log.info("积分回退({}): user={} {} -> {} (撤销对局 {})",
                    MatchFormatUtil.categoryName(category),
                    h.getUserId(), h.getRatingAfter(), h.getRatingBefore(), match.getId());
        }
    }

    private void apply(EloCalculator.TeamResult team, Long matchId, boolean won, int category) {
        for (EloCalculator.PlayerResult r : team.players()) {
            User user = userMapper.selectById(r.userId());
            if (user == null) {
                continue;
            }
            // 分类积分 + 分类胜负场
            setCategoryRating(user, category, r.ratingAfter());
            incCategoryResult(user, category, won);
            // 全局综合场次照旧累加
            user.setGamesPlayed(nullSafe(user.getGamesPlayed()) + 1);
            if (won) {
                user.setWinCount(nullSafe(user.getWinCount()) + 1);
            } else {
                user.setLossCount(nullSafe(user.getLossCount()) + 1);
            }
            refreshOverallRating(user);
            userMapper.updateById(user);

            RatingHistory history = new RatingHistory();
            history.setUserId(r.userId());
            history.setMatchId(matchId);
            history.setCategory(category);
            history.setRatingBefore(r.ratingBefore());
            history.setRatingAfter(r.ratingAfter());
            history.setDelta(r.delta());
            history.setKFactor(r.kFactor());
            history.setExpected(BigDecimal.valueOf(r.expected()).setScale(4, RoundingMode.HALF_UP));
            history.setMarginDelta(r.marginDelta());
            history.setGenderAdjust(BigDecimal.valueOf(team.genderAdjust()).setScale(2, RoundingMode.HALF_UP));
            ratingHistoryMapper.insert(history);

            // 同步对应类别的 Redis 积分榜
            rankingService.updateScore(r.userId(), r.ratingAfter(), category);

            log.info("积分更新({}): user={} {} -> {} (基础{}{}, 分差加成{}{}, K={}, E={})",
                    MatchFormatUtil.categoryName(category),
                    r.userId(), r.ratingBefore(), r.ratingAfter(),
                    r.delta() - r.marginDelta() >= 0 ? "+" : "", r.delta() - r.marginDelta(),
                    r.marginDelta() >= 0 ? "+" : "", r.marginDelta(), r.kFactor(),
                    String.format("%.3f", r.expected()));
        }
    }

    /** 按类别读取入场积分与分类场次（K 因子按分类场次计算） */
    private List<PlayerInput> loadTeam(List<Long> userIds, int category) {
        return userIds.stream()
                .map(id -> {
                    User u = userMapper.selectById(id);
                    if (u == null) {
                        return null;
                    }
                    // gender 供 ELO 性别修正使用（男双打女双/混双时压低女方期望）
                    int gender = u.getGender() == null ? Constants.GENDER_UNKNOWN : u.getGender();
                    Integer rating = categoryRating(u, category);
                    Integer played = categoryPlayed(u, category);
                    if (rating == null) {
                        rating = u.getRating() != null ? u.getRating() : 1200;
                    }
                    return new PlayerInput(u.getId(), rating, played == null ? 0 : played, gender);
                })
                .filter(p -> p != null)
                .toList();
    }

    /* ---------- 分类字段读写 helpers ---------- */

    private static Integer categoryRating(User u, int category) {
        return switch (category) {
            case Constants.CATEGORY_DOUBLES -> u.getRatingDoubles();
            case Constants.CATEGORY_MIXED -> u.getRatingMixed();
            default -> u.getRatingSingles();
        };
    }

    private static Integer categoryPlayed(User u, int category) {
        return switch (category) {
            case Constants.CATEGORY_DOUBLES -> u.getDoublesPlayed();
            case Constants.CATEGORY_MIXED -> u.getMixedPlayed();
            default -> u.getSinglesPlayed();
        };
    }

    private static void setCategoryRating(User u, int category, Integer rating) {
        switch (category) {
            case Constants.CATEGORY_DOUBLES -> u.setRatingDoubles(rating);
            case Constants.CATEGORY_MIXED -> u.setRatingMixed(rating);
            default -> u.setRatingSingles(rating);
        }
    }

    private static void incCategoryResult(User u, int category, boolean won) {
        switch (category) {
            case Constants.CATEGORY_DOUBLES -> {
                u.setDoublesPlayed(nullSafe(u.getDoublesPlayed()) + 1);
                if (won) u.setDoublesWin(nullSafe(u.getDoublesWin()) + 1);
                else u.setDoublesLoss(nullSafe(u.getDoublesLoss()) + 1);
            }
            case Constants.CATEGORY_MIXED -> {
                u.setMixedPlayed(nullSafe(u.getMixedPlayed()) + 1);
                if (won) u.setMixedWin(nullSafe(u.getMixedWin()) + 1);
                else u.setMixedLoss(nullSafe(u.getMixedLoss()) + 1);
            }
            default -> {
                u.setSinglesPlayed(nullSafe(u.getSinglesPlayed()) + 1);
                if (won) u.setSinglesWin(nullSafe(u.getSinglesWin()) + 1);
                else u.setSinglesLoss(nullSafe(u.getSinglesLoss()) + 1);
            }
        }
    }

    /** 回退时扣减分类胜负场；counted=false 表示既非胜也非负（成员缺失的边缘情况），只扣场次 */
    private static void decCategoryResult(User u, int category, boolean won, boolean counted) {
        switch (category) {
            case Constants.CATEGORY_DOUBLES -> {
                u.setDoublesPlayed(Math.max(0, nullSafe(u.getDoublesPlayed()) - 1));
                if (won) u.setDoublesWin(Math.max(0, nullSafe(u.getDoublesWin()) - 1));
                else if (counted) u.setDoublesLoss(Math.max(0, nullSafe(u.getDoublesLoss()) - 1));
            }
            case Constants.CATEGORY_MIXED -> {
                u.setMixedPlayed(Math.max(0, nullSafe(u.getMixedPlayed()) - 1));
                if (won) u.setMixedWin(Math.max(0, nullSafe(u.getMixedWin()) - 1));
                else if (counted) u.setMixedLoss(Math.max(0, nullSafe(u.getMixedLoss()) - 1));
            }
            default -> {
                u.setSinglesPlayed(Math.max(0, nullSafe(u.getSinglesPlayed()) - 1));
                if (won) u.setSinglesWin(Math.max(0, nullSafe(u.getSinglesWin()) - 1));
                else if (counted) u.setSinglesLoss(Math.max(0, nullSafe(u.getSinglesLoss()) - 1));
            }
        }
    }

    /** 综合 rating = 三类分均值（四舍五入），让 NavHeader / 报名标签等综合展示持续更新 */
    private static void refreshOverallRating(User u) {
        int sum = nullSafe(u.getRatingSingles()) + nullSafe(u.getRatingDoubles()) + nullSafe(u.getRatingMixed());
        u.setRating((int) Math.round(sum / 3.0));
    }

    private static int nullSafe(Integer v) {
        return v == null ? 0 : v;
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
