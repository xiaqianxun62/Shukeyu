package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.dto.StatsOverviewResponse;
import com.yuyue.dto.StatsOverviewResponse.BucketCount;
import com.yuyue.dto.StatsOverviewResponse.DayCount;
import com.yuyue.entity.Game;
import com.yuyue.entity.MatchGame;
import com.yuyue.entity.Registration;
import com.yuyue.entity.User;
import com.yuyue.mapper.GameMapper;
import com.yuyue.mapper.MatchGameMapper;
import com.yuyue.mapper.RegistrationMapper;
import com.yuyue.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页数据概览：总量指标 + 近 7 日场次 + ELO 分布。
 * 全部为聚合数字（无个人信息），公开接口使用；查询轻量（count + 两条 group by）。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");
    private static final String[] BUCKET_LABELS = {"<1000", "1000-1199", "1200-1399", "≥1400"};

    private final UserMapper userMapper;
    private final GameMapper gameMapper;
    private final MatchGameMapper matchGameMapper;
    private final RegistrationMapper registrationMapper;

    public StatsOverviewResponse overview() {
        long users = nullSafe(userMapper.selectCount(new LambdaQueryWrapper<User>()));
        long games = nullSafe(gameMapper.selectCount(new LambdaQueryWrapper<Game>()));
        long matches = nullSafe(matchGameMapper.selectCount(new LambdaQueryWrapper<MatchGame>()));
        long registrations = nullSafe(registrationMapper.selectCount(new LambdaQueryWrapper<Registration>()));

        return StatsOverviewResponse.builder()
                .totalUsers(users)
                .totalGames(games)
                .totalMatches(matches)
                .totalRegistrations(registrations)
                .last7Days(last7Days())
                .ratingBuckets(ratingBuckets())
                .build();
    }

    /** 近 7 日（含今天）每日对局数，空天补 0，按天升序 */
    private List<DayCount> last7Days() {
        LocalDateTime start = LocalDate.now().minusDays(6).atStartOfDay();
        Map<String, Long> byDay = new HashMap<>();
        for (DayCount d : matchGameMapper.countByDaySince(start)) {
            if (d.getDate() != null && d.getCount() != null) {
                byDay.put(d.getDate(), d.getCount());
            }
        }
        List<DayCount> out = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            String key = LocalDate.now().minusDays(i).format(MD);
            out.add(DayCount.builder().date(key).count(byDay.getOrDefault(key, 0L)).build());
        }
        return out;
    }

    /** ELO 分布四段，缺段补 0，固定顺序 */
    private List<BucketCount> ratingBuckets() {
        long[] counts = new long[4];
        for (Map<String, Object> row : userMapper.ratingBucketCounts()) {
            Object bucket = row.get("bucket");
            Object cnt = row.get("cnt");
            if (bucket instanceof Number b && cnt instanceof Number c) {
                int idx = b.intValue();
                if (idx >= 0 && idx < 4) {
                    counts[idx] = c.longValue();
                }
            }
        }
        List<BucketCount> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            out.add(BucketCount.builder().label(BUCKET_LABELS[i]).count(counts[i]).build());
        }
        return out;
    }

    private static long nullSafe(Long v) {
        return v == null ? 0 : v;
    }
}
