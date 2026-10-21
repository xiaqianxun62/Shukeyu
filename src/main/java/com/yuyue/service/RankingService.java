package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.Constants;
import com.yuyue.dto.RankingItem;
import com.yuyue.entity.User;
import com.yuyue.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 积分榜：按类别三张 Redis ZSet 承载热点读（官网与小程序同源），定时从 MySQL 全量校准。
 * 类别：1单打（复用原 ZSet key）2同性别双打 3混双。
 * 每张榜只显示该类别打过比赛（对应 *_win + *_loss > 0）的用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    private static final int MAX_TOP_N = 100;

    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;

    /** 类别 → Redis ZSet key（1=存量 key，数据无缝过渡） */
    private static String zsetKey(int category) {
        return switch (category) {
            case Constants.CATEGORY_DOUBLES -> Constants.RANKING_ZSET_DOUBLES;
            case Constants.CATEGORY_MIXED -> Constants.RANKING_ZSET_MIXED;
            default -> Constants.RANKING_ZSET;
        };
    }

    /**
     * 查询某类别积分榜 Top N；对应 ZSet 为空时回源 MySQL 重建
     */
    public List<RankingItem> topN(int n, int category) {
        int limit = Math.min(Math.max(n, 1), MAX_TOP_N);
        String key = zsetKey(category);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L);

        if (tuples == null || tuples.isEmpty()) {
            rebuildCategory(category);
            tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L);
            if (tuples == null || tuples.isEmpty()) {
                return List.of();
            }
        }

        List<RankingItem> result = new ArrayList<>(tuples.size());
        int rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Long userId = Long.valueOf(tuple.getValue());
            User user = userMapper.selectById(userId);
            if (user == null) {
                continue;
            }
            Integer catRating = categoryRating(user, category);
            Integer catWin = categoryWin(user, category);
            Integer catLoss = categoryLoss(user, category);
            // 榜单展示全部按该类别口径
            result.add(RankingItem.builder()
                    .userId(userId)
                    .name(displayName(user))
                    .rating(tuple.getScore() == null ? nullSafe(catRating) : tuple.getScore().intValue())
                    .win(nullSafe(catWin))
                    .loss(nullSafe(catLoss))
                    .rank(rank++)
                    .avatar(user.getAvatar())
                    .build());
        }
        return result;
    }

    /**
     * 积分榜展示名：由每人自己选（user.name_display，0 真实姓名 1 昵称，默认昵称）。
     * 只决定这一行（本人）显示什么，选中的那个为空时回退另一个，都没有则按 userId 兜底。
     */
    private static String displayName(User user) {
        boolean preferNickname = user.getNameDisplay() == null || user.getNameDisplay() != 0;
        String nickname = user.getNickname() == null || user.getNickname().isBlank()
                ? null : user.getNickname().trim();
        String name = user.getName() == null || user.getName().isBlank() ? null : user.getName();
        if (preferNickname) {
            if (nickname != null) return nickname;
            if (name != null) return name;
        } else {
            if (name != null) return name;
            if (nickname != null) return nickname;
        }
        return "球友" + user.getId();
    }

    /** 榜单更新某个用户在某类别的积分（结算后调用） */
    public void updateScore(Long userId, int rating, int category) {
        redisTemplate.opsForZSet().add(zsetKey(category), String.valueOf(userId), rating);
    }

    /** 从 MySQL 全量重建三张榜单（只保留对应类别打过比赛的用户） */
    public void rebuildFromDb() {
        rebuildCategory(Constants.CATEGORY_SINGLES);
        rebuildCategory(Constants.CATEGORY_DOUBLES);
        rebuildCategory(Constants.CATEGORY_MIXED);
    }

    /** 重建单张类别榜 */
    private void rebuildCategory(int category) {
        String key = zsetKey(category);
        String playedCond = switch (category) {
            case Constants.CATEGORY_DOUBLES -> "(COALESCE(doubles_win, 0) + COALESCE(doubles_loss, 0)) > 0";
            case Constants.CATEGORY_MIXED -> "(COALESCE(mixed_win, 0) + COALESCE(mixed_loss, 0)) > 0";
            default -> "(COALESCE(singles_win, 0) + COALESCE(singles_loss, 0)) > 0";
        };
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>().apply(playedCond));
        redisTemplate.delete(key);
        if (users.isEmpty()) {
            log.info("积分榜重建({})：该类别暂无打过比赛的用户", category);
            return;
        }
        users.forEach(u -> {
            Integer rating = categoryRating(u, category);
            if (rating != null) {
                redisTemplate.opsForZSet().add(key, String.valueOf(u.getId()), rating);
            }
        });
        log.info("积分榜已从 MySQL 重建({}): {} 人", category, users.size());
    }

    /** 每 10 分钟校准一次 Redis 与 MySQL 的一致性 */
    @Scheduled(fixedDelayString = "600000", initialDelay = 30000)
    public void syncPeriodically() {
        rebuildFromDb();
    }

    /* ---------- 分类字段读取 ---------- */

    private static Integer categoryRating(User u, int category) {
        return switch (category) {
            case Constants.CATEGORY_DOUBLES -> u.getRatingDoubles();
            case Constants.CATEGORY_MIXED -> u.getRatingMixed();
            default -> u.getRatingSingles();
        };
    }

    private static Integer categoryWin(User u, int category) {
        return switch (category) {
            case Constants.CATEGORY_DOUBLES -> u.getDoublesWin();
            case Constants.CATEGORY_MIXED -> u.getMixedWin();
            default -> u.getSinglesWin();
        };
    }

    private static Integer categoryLoss(User u, int category) {
        return switch (category) {
            case Constants.CATEGORY_DOUBLES -> u.getDoublesLoss();
            case Constants.CATEGORY_MIXED -> u.getMixedLoss();
            default -> u.getSinglesLoss();
        };
    }

    private static int nullSafe(Integer v) {
        return v == null ? 0 : v;
    }
}
