package com.yuyue.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.Constants;
import com.yuyue.entity.Game;
import com.yuyue.entity.Registration;
import com.yuyue.mapper.GameMapper;
import com.yuyue.mapper.RegistrationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「我报了哪些球局 / 我发布的球局」。
 * <p>
 * 只列「今天及以后、还没结束」的，历史球局对问答没价值，还会把提示词塞满。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MyGamesTool implements AiDataTool {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("M月d日");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    /** 最近报名记录的扫描上限：用户可能报过很多历史球局，取最近 50 条足够覆盖未来场次 */
    private static final int SCAN_LIMIT = 50;
    private static final int MAX_SHOW = 5;

    private static final String[] KEYWORDS = {
            "我报名", "我报的", "我参加", "我的球局", "我发布的", "我发起", "我要打", "我接下来", "我的场次"
    };

    private final RegistrationMapper registrationMapper;
    private final GameMapper gameMapper;

    @Override
    public String name() {
        return "MY_GAMES";
    }

    @Override
    public String label() {
        return "我的球局";
    }

    @Override
    public boolean supports(String question) {
        if (question == null) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        for (String keyword : KEYWORDS) {
            if (q.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String fetch(Long userId) {
        if (userId == null) {
            return "";
        }
        List<Registration> registrations = registrationMapper.selectList(
                new LambdaQueryWrapper<Registration>()
                        .eq(Registration::getUserId, userId)
                        .orderByDesc(Registration::getId)
                        .last("LIMIT " + SCAN_LIMIT));

        Map<Long, Registration> registrationByGame = new LinkedHashMap<>();
        for (Registration registration : registrations) {
            registrationByGame.putIfAbsent(registration.getGameId(), registration);
        }

        LocalDate today = LocalDate.now();
        List<Game> upcoming = new ArrayList<>();
        if (!registrationByGame.isEmpty()) {
            List<Game> games = gameMapper.selectBatchIds(registrationByGame.keySet());
            for (Game game : games) {
                if (game.getPlayDate() != null && !game.getPlayDate().isBefore(today)
                        && game.getStatus() != null
                        && (game.getStatus() == Constants.GAME_STATUS_OPEN
                        || game.getStatus() == Constants.GAME_STATUS_ARRANGED)) {
                    upcoming.add(game);
                }
            }
        }
        upcoming.sort(Comparator.comparing(Game::getPlayDate)
                .thenComparing(g -> g.getStartTime() == null ? java.time.LocalTime.MAX : g.getStartTime()));

        StringBuilder sb = new StringBuilder();
        if (upcoming.isEmpty()) {
            sb.append("- 我报名的未来球局：暂无（可能都打完了，或还没报名）\n");
        } else {
            sb.append("- 我报名的未来球局（最多列 ").append(MAX_SHOW).append(" 个）：\n");
            for (Game game : upcoming.stream().limit(MAX_SHOW).toList()) {
                sb.append("  · ").append(describe(game))
                        .append(game.getStatus() == Constants.GAME_STATUS_ARRANGED ? "（已编排）" : "（报名中）")
                        .append('\n');
            }
        }

        Long createdOpen = gameMapper.selectCount(new LambdaQueryWrapper<Game>()
                .eq(Game::getCreatorId, userId)
                .eq(Game::getStatus, Constants.GAME_STATUS_OPEN)
                .ge(Game::getPlayDate, today));
        if (createdOpen != null && createdOpen > 0) {
            sb.append("- 我发布的、还在报名的球局：").append(createdOpen).append(" 个\n");
        }
        return sb.toString();
    }

    private String describe(Game game) {
        String date = game.getPlayDate() == null ? "待定" : game.getPlayDate().format(DATE_FMT);
        String time = game.getStartTime() == null ? "" : " " + game.getStartTime().format(TIME_FMT);
        String location = game.getLocation() == null || game.getLocation().isBlank() ? "" : "｜" + game.getLocation();
        return date + time + location + "｜" + game.getTitle();
    }
}
