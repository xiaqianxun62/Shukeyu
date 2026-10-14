package com.yuyue.common;

/**
 * 全局常量：Redis Key、业务枚举值
 */
public final class Constants {

    private Constants() {
    }

    /* ---------- Redis Keys ---------- */

    /** 积分榜 ZSet：member=userId, score=rating */
    public static final String RANKING_ZSET = "yuyue:ranking:elo";

    /** 球局报名人数 Hash：field=gameId, value=count */
    public static final String GAME_REG_COUNT = "yuyue:game:reg:count";

    /** JWT 黑名单（登出后 token 失效）：member=token */
    public static final String JWT_BLACKLIST = "yuyue:jwt:blacklist";

    /* ---------- 枚举值 ---------- */

    /** 性别：男 */
    public static final int GENDER_MALE = 1;
    /** 性别：女 */
    public static final int GENDER_FEMALE = 2;

    /** 球局状态：报名中 */
    public static final int GAME_STATUS_OPEN = 0;
    /** 球局状态：已编排（对阵已生成，等待打球 / 计分） */
    public static final int GAME_STATUS_ARRANGED = 1;
    /** 球局状态：已结束（所有对阵都已录入比分） */
    public static final int GAME_STATUS_FINISHED = 2;

    /** 球局发布模式：预报名（定好时间地点，提前约人） */
    public static final int GAME_MODE_PREBOOK = 0;
    /** 球局发布模式：现场报名（只定人数与场地，人齐后由发起人编排，现场开打） */
    public static final int GAME_MODE_ONSITE = 1;

    /** 对局形式：单打/男双/女双/混双 */
    public static final int FORMAT_SINGLES = 1;
    public static final int FORMAT_MEN_DOUBLES = 2;
    public static final int FORMAT_WOMEN_DOUBLES = 3;
    public static final int FORMAT_MIXED_DOUBLES = 4;
    /** 随机双打：不分性别随机组队，仅用于 ELO 试算，不落库 */
    public static final int FORMAT_RANDOM_DOUBLES = 5;

    /** 对局胜方：A队 / B队；0 表示还没出结果（未计分或比分已撤销） */
    public static final int WINNER_NONE = 0;
    public static final int WINNER_A = 1;
    public static final int WINNER_B = 2;

    /**
     * 轮排的性别组队规则（只影响双打怎么配队；单打时不生效）
     * 0 不分性别（按积分均衡搭配） 1 尽量男双 2 尽量女双 3 尽量混双（每队一男一女）
     * 4 男女分场（男双 + 女双同时进行，按男女比分配场地，互不干扰）
     */
    public static final int GENDER_RULE_ANY = 0;
    public static final int GENDER_RULE_MEN = 1;
    public static final int GENDER_RULE_WOMEN = 2;
    public static final int GENDER_RULE_MIXED = 3;
    /** 男女分场：按报名男女比分配场地，部分场地开男双、其余开女双，男女队员互不干扰 */
    public static final int GENDER_RULE_SEPARATE = 4;

    /** 用户管理员标记 */
    public static final int ADMIN_NO = 0;
    public static final int ADMIN_YES = 1;

    /** 报名展示方式：匿名（对外只显示「球友#xxxx」） */
    public static final int ANONYMOUS_YES = 1;
    /** 报名展示方式：实名（报名列表对登录用户显示真实姓名） */
    public static final int ANONYMOUS_NO = 0;

    /** 对局结算状态 */
    public static final int SETTLE_PENDING = 0;
    public static final int SETTLE_DONE = 1;

    /** 计分日志动作：首次计分（该操作人即本场裁判） */
    public static final int SCORE_ACTION_FIRST = 1;
    /** 计分日志动作：修改比分 */
    public static final int SCORE_ACTION_EDIT = 2;
    /** 计分日志动作：撤销比分（录 0:0，误点救回；本场不计输赢，已结算的积分原路退回） */
    public static final int SCORE_ACTION_RESET = 3;
}
