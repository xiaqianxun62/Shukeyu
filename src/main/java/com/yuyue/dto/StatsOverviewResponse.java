package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 首页数据概览：总量指标 + 近 7 日场次趋势 + ELO 分布。
 * 公开接口（官网首页未登录也展示），只含聚合数字，不含任何个人信息。
 */
@Data
@Builder
public class StatsOverviewResponse {

    /** 用户总数 */
    private Long totalUsers;

    /** 球局总数 */
    private Long totalGames;

    /** 对局（match_game）总数 */
    private Long totalMatches;

    /** 报名人次 */
    private Long totalRegistrations;

    /** 近 7 日每日对局数（按天升序，空天补 0） */
    private List<DayCount> last7Days;

    /** ELO 分布（综合分四段，固定 4 项） */
    private List<BucketCount> ratingBuckets;

    @Data
    @Builder
    public static class DayCount {
        /** MM-dd */
        private String date;
        private Long count;
    }

    @Data
    @Builder
    public static class BucketCount {
        /** 段位标签：如 <1000 */
        private String label;
        private Long count;
    }
}
