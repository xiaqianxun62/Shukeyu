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
 * 积分榜：Redis ZSet 承载热点读（官网与小程序同源），定时从 MySQL 全量校准
 * 只显示打过比赛（win_count + loss_count > 0）的用户
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    //private static final int DEFAULT_TOP_N = 10;
    private static final int MAX_TOP_N = 100;

    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;

    /**
     * 查询积分榜 Top N；ZSet 为空时回源 MySQL 并重建
     */
    public List<RankingItem> topN(int n) {
        int limit = Math.min(Math.max(n, 1), MAX_TOP_N);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(Constants.RANKING_ZSET, 0, limit - 1L);

        if (tuples == null || tuples.isEmpty()) {
            rebuildFromDb();
            tuples = redisTemplate.opsForZSet()
                    .reverseRangeWithScores(Constants.RANKING_ZSET, 0, limit - 1L);
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
            result.add(RankingItem.builder()
                    .userId(userId)
                    .name(displayName(user))
                    .rating(tuple.getScore() == null ? user.getRating() : tuple.getScore().intValue())
                    .win(user.getWinCount())
                    .loss(user.getLossCount())
                    .rank(rank++)
                    .avatar(user.getAvatar())
                    .build());
        }
        return result;
    }

    /**
     * 积分榜展示名：优先昵称（非姓名展示场景），未设昵称回退真实姓名，
     * 姓名也没有时按 userId 兜底。
     */
    private static String displayName(User user) {
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname().trim();
        }
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return "球友" + user.getId();
    }

    /** 榜单更新某个用户积分（结算后调用） */
    public void updateScore(Long userId, int rating) {
        redisTemplate.opsForZSet().add(Constants.RANKING_ZSET, String.valueOf(userId), rating);
    }

    /** 从 MySQL 全量重建榜单（只保留打过比赛的用户） */
    public void rebuildFromDb() {
        // 只取 win_count + loss_count > 0 的用户（至少打过一场已结算比赛）
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .apply("(COALESCE(win_count, 0) + COALESCE(loss_count, 0)) > 0"));
        if (users.isEmpty()) {
            redisTemplate.delete(Constants.RANKING_ZSET);
            log.info("积分榜重建：暂无打过比赛的用户");
            return;
        }
        redisTemplate.delete(Constants.RANKING_ZSET);
        users.forEach(u -> redisTemplate.opsForZSet()
                .add(Constants.RANKING_ZSET, String.valueOf(u.getId()), u.getRating()));
        log.info("积分榜已从 MySQL 重建（仅打过比赛）: {} 人", users.size());
    }

    /** 每 10 分钟校准一次 Redis 与 MySQL 的一致性 */
    @Scheduled(fixedDelayString = "600000", initialDelay = 30000)
    public void syncPeriodically() {
        rebuildFromDb();
    }
}
