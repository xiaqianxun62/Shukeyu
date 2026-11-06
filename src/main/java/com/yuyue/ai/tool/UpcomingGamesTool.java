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
import java.util.List;
import java.util.Locale;

/**
 * 「最近有什么球局 / 还有名额吗」：列最近 5 个开放报名的球局与剩余名额。
 * <p>
 * 与 {@link MyGamesTool} 的分工：这个不带用户身份（游客视角），因此不需要登录也能给出有用信息；
 * 命中「我的」类问题时让位给对方，避免同一段事实注入两遍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpcomingGamesTool implements AiDataTool {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("M月d日");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_SHOW = 5;

    private static final String[] KEYWORDS = {
            "有什么球局", "有哪些球局", "最近球局", "近期球局", "本周球局", "这周球局",
            "周末球局", "能报名", "可以报名", "还有名额", "球局推荐", "找球局"
    };
    private static final String[] EXCLUDES = {"我报名", "我报的", "我参加", "我的球局", "我发布的"};

    private final GameMapper gameMapper;
    private final RegistrationMapper registrationMapper;

    @Override
    public String name() {
        return "UPCOMING_GAMES";
    }

    @Override
    public String label() {
        return "近期开放球局";
    }

    @Override
    public boolean supports(String question) {
        if (question == null) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        for (String exclude : EXCLUDES) {
            if (q.contains(exclude)) {
                return false;
            }
        }
        for (String keyword : KEYWORDS) {
            if (q.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String fetch(Long userId) {
        List<Game> games = gameMapper.selectList(new LambdaQueryWrapper<Game>()
                .eq(Game::getStatus, Constants.GAME_STATUS_OPEN)
                .and(w -> w.eq(Game::getHidden, 0).or().isNull(Game::getHidden))
                .ge(Game::getPlayDate, LocalDate.now())
                .orderByAsc(Game::getPlayDate)
                .orderByAsc(Game::getStartTime)
                .last("LIMIT " + MAX_SHOW));

        if (games.isEmpty()) {
            return "- 近期暂无开放报名的球局\n";
        }

        StringBuilder sb = new StringBuilder("- 近期开放报名的球局：\n");
        for (Game game : games) {
            Long registered = registrationMapper.selectCount(new LambdaQueryWrapper<Registration>()
                    .eq(Registration::getGameId, game.getId()));
            int used = registered == null ? 0 : registered.intValue();
            int max = game.getMaxPlayers() == null ? 0 : game.getMaxPlayers();
            int left = Math.max(max - used, 0);

            String date = game.getPlayDate() == null ? "待定" : game.getPlayDate().format(DATE_FMT);
            String time = game.getStartTime() == null ? "" : " " + game.getStartTime().format(TIME_FMT);
            String location = game.getLocation() == null || game.getLocation().isBlank()
                    ? "" : "｜" + game.getLocation();
            sb.append("  · ").append(date).append(time).append(location)
                    .append("｜").append(game.getTitle())
                    .append("｜").append(used).append('/').append(max)
                    .append(" 人，剩 ").append(left).append(" 个名额\n");
        }
        return sb.toString();
    }
}
