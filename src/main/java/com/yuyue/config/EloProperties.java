package com.yuyue.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "yuyue.elo")
public class EloProperties {

    /** 新用户默认积分 */
    private int defaultRating = 1200;

    /** ≤20 场 K 值 */
    private int kNew = 40;

    /** 21-60 场 K 值 */
    private int kMid = 24;

    /** >60 场 K 值 */
    private int kPro = 16;

    /* ==================== 性别修正（跨赛制对局） ==================== */

    /** 是否启用性别修正。关闭后退回纯积分比较，行为与旧版本完全一致 */
    private boolean genderAdjustEnabled = true;

    /**
     * 男生跨性别对战的天然优势（分）。
     * 男生场上只要对手队伍里有女生，就按「对手女生占比 × 该值」折算占便宜。
     */
    private double maleVsFemaleEdge = 100;

    /**
     * 女生跨性别对战的天然劣势（分）。
     * 女生场上只要对手队伍里有男生，就按「对手男生占比 × 该值」折算吃亏。
     */
    private double femaleVsMaleEdge = 100;

    /* ==================== 局内分差动态调整 ==================== */

    /** 是否启用局内分差加成 */
    private boolean marginBonusEnabled = true;

    /** 局内分差基准分：羽毛球一局 21 分制，净胜分以该值为满格 */
    private int marginWinScore = 21;

    /**
     * 分差加成触发线（占满格的比例）。
     * 净胜分不超过该比例时视为苦战、不给加成；21 分制下约等于净胜 5 分以内不触发。
     */
    private double marginFloor = 0.25;

    /** 分差加成系数：净胜分打满时，胜方额外获得 K × 该系数 分 */
    private double marginBonusRate = 0.5;
}
