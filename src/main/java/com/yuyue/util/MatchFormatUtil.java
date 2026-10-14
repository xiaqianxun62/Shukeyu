package com.yuyue.util;

import java.util.List;
import java.util.Map;

/**
 * 对阵构成判定。
 *
 * <p>同一场里两队的性别构成可以不同（例如 A 队男双 vs B 队混双），
 * 只用一个 format 标注整场会显示成「混双打混双」这种误导文案，
 * 所以按队分别判定展示名，由前端显示成「男双 vs 混双」。
 *
 * <p>性别信息不全（报名者没填性别）时返回 null，由调用方用整场 format 兜底。
 */
public final class MatchFormatUtil {

    private MatchFormatUtil() {}

    /**
     * 按一队的实际性别构成给出展示名。
     *
     * @param team        队员 userId（单打 1 人、双打 2 人）
     * @param genderById  userId → 性别（1男 2女）
     * @return 单打 / 男双 / 女双 / 混双；无法判定时返回 null
     */
    public static String sideFormatName(List<Long> team, Map<Long, Integer> genderById) {
        if (team == null || team.isEmpty()) {
            return null;
        }
        if (team.size() == 1) {
            return "单打";
        }
        int male = 0;
        int female = 0;
        int unknown = 0;
        for (Long id : team) {
            Integer g = genderById == null ? null : genderById.get(id);
            if (g != null && g == 1) {
                male++;
            } else if (g != null && g == 2) {
                female++;
            } else {
                unknown++;
            }
        }
        if (unknown > 0) {
            return null;
        }
        if (male == team.size()) {
            return "男双";
        }
        if (female == team.size()) {
            return "女双";
        }
        return "混双";
    }
}
