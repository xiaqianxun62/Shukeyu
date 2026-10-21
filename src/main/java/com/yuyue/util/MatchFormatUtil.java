package com.yuyue.util;

import com.yuyue.common.Constants;

import java.util.List;
import java.util.Map;

/**
 * 对阵规则的唯一出口：赛制文案、组队人数、一队的实际构成。
 *
 * <p>这几条知识过去散落在 RotationService / EloSimulateService / TournamentService /
 * RotationEngine / MatchService 各写一遍，同一件事有三四种写法：
 * 已知赛制的文案三份完全相同（兜底措辞各不一样），
 * 「每队几人」「男双需要两位男生」也是各写各的。
 * 新增模块照抄时必然产生行为漂移，故统一收口。
 */
public final class MatchFormatUtil {

    /** 可以开赛落库的赛制（不含仅供试算的随机双打） */
    private static final List<Integer> STORABLE_FORMATS = List.of(
            Constants.FORMAT_SINGLES,
            Constants.FORMAT_MEN_DOUBLES,
            Constants.FORMAT_WOMEN_DOUBLES,
            Constants.FORMAT_MIXED_DOUBLES);

    private MatchFormatUtil() {}

    /**
     * 对阵形式的展示名。
     *
     * <p>无法识别时统一回「未知」——宁可显示未知，也不要像过去那样
     * 兜底成「双打」把非法值说成合法赛制。
     */
    public static String formatName(Integer format) {
        if (format == null) {
            return "未知";
        }
        return switch (format) {
            case Constants.FORMAT_SINGLES -> "单打";
            case Constants.FORMAT_MEN_DOUBLES -> "男双";
            case Constants.FORMAT_WOMEN_DOUBLES -> "女双";
            case Constants.FORMAT_MIXED_DOUBLES -> "混双";
            case Constants.FORMAT_RANDOM_DOUBLES -> "随机双打";
            default -> "未知";
        };
    }

    /** 每队人数：单打 1 人，其余 2 人 */
    public static int teamSize(int format) {
        return format == Constants.FORMAT_SINGLES ? 1 : 2;
    }

    /**
     * 对局形式 → ELO 类别：1单打→单打，2男双/3女双→双打（同性别），4混双→混双，
     * 5随机双打（仅试算）按双打计；无法识别按单打兜底。
     */
    public static int categoryOfFormat(int format) {
        return switch (format) {
            case Constants.FORMAT_MEN_DOUBLES, Constants.FORMAT_WOMEN_DOUBLES,
                 Constants.FORMAT_RANDOM_DOUBLES -> Constants.CATEGORY_DOUBLES;
            case Constants.FORMAT_MIXED_DOUBLES -> Constants.CATEGORY_MIXED;
            default -> Constants.CATEGORY_SINGLES;
        };
    }

    /** ELO 类别展示名：单打 / 双打 / 混双 */
    public static String categoryName(int category) {
        return switch (category) {
            case Constants.CATEGORY_DOUBLES -> "双打";
            case Constants.CATEGORY_MIXED -> "混双";
            default -> "单打";
        };
    }

    /**
     * 是否是<b>可落库</b>的赛制值。
     *
     * <p>注意不含 {@link Constants#FORMAT_RANDOM_DOUBLES}——随机双打只用于 ELO 试算，
     * 不是能开赛的真实组别，不能进 {@code match_game} / {@code tournament_match}。
     *
     * <p>过去 TournamentService 用一张「赛制 → 文案」的 Map 顺带做合法性校验，
     * 语义含混（看起来像在查文案，实际在查合法性），故拆成独立方法。
     */
    public static boolean isStorableFormat(Integer format) {
        return format != null && STORABLE_FORMATS.contains(format);
    }

    /**
     * 按一队的实际性别构成给出展示名。
     *
     * <p>同一场里两队的性别构成可以不同（例如 A 队男双 vs B 队混双），
     * 只用一个 format 标注整场会显示成「混双打混双」这种误导文案，
     * 所以按队分别判定展示名，由前端显示成「男双 vs 混双」。
     *
     * <p>性别信息不全（报名者没填性别）时返回 null，由调用方用整场 format 兜底。
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