package com.yuyue.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.entity.User;
import com.yuyue.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 「我的积分 / 战绩 / 排名」——一次查库就能答，不值得让模型自己拼 SQL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MyRatingTool implements AiDataTool {

    private static final String[] KEYWORDS = {
            "我的积分", "我多少分", "我几分", "我的分", "我的排名", "我排第几", "我的战绩",
            "我的胜率", "我赢了几场", "我打了几场", "单打积分", "双打积分", "混双积分"
    };

    private final UserMapper userMapper;

    @Override
    public String name() {
        return "MY_RATING";
    }

    @Override
    public String label() {
        return "我的积分与战绩";
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
        User user = userMapper.selectById(userId);
        if (user == null) {
            return "";
        }
        int rating = nullSafe(user.getRating(), 1200);
        int total = nullSafe(user.getGamesPlayed(), 0);
        int win = nullSafe(user.getWinCount(), 0);

        // 「比我高的人数 + 1」= 注册用户中的位次；不打球的人也在表里，所以文案要说「约」
        Long better = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .gt(User::getRating, rating));
        long rank = (better == null ? 0L : better) + 1;

        StringBuilder sb = new StringBuilder();
        sb.append("- 昵称：").append(displayName(user)).append('\n');
        // 统一用 \n 而不是 %n：%n 在 Windows 上是 \r\n，会把 CRLF 混进提示词
        sb.append(String.format("- 综合积分 %d，在注册用户中约第 %d 位\n", rating, rank));
        sb.append(String.format("- 分类积分：单打 %d / 同性别双打 %d / 混双 %d\n",
                nullSafe(user.getRatingSingles(), rating),
                nullSafe(user.getRatingDoubles(), rating),
                nullSafe(user.getRatingMixed(), rating)));
        sb.append(String.format("- 总战绩：%d 场，%d 胜 %d 负", total, win,
                nullSafe(user.getLossCount(), 0)));
        if (total > 0) {
            sb.append(String.format("，胜率 %.0f%%", win * 100.0 / total));
        }
        sb.append('\n');
        sb.append(String.format("- 分类场次：单打 %d 场 / 双打 %d 场 / 混双 %d 场",
                nullSafe(user.getSinglesPlayed(), 0),
                nullSafe(user.getDoublesPlayed(), 0),
                nullSafe(user.getMixedPlayed(), 0)));
        return sb.toString();
    }

    private String displayName(User user) {
        boolean preferNickname = nullSafe(user.getNameDisplay(), 1) == 1;
        String nickname = user.getNickname();
        if (preferNickname && nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        return user.getName() == null ? "球友" : user.getName();
    }

    private int nullSafe(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
