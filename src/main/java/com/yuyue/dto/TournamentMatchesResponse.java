package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 赛程录入页数据：按组别把对阵拆成「待录入 / 已出结果」两栏
 *
 * <p>现场只需要回答一个问题「这场谁赢了」，所以把待录入的排在最上面、已完成的沉到底部，
 * 一屏之内就能把所有没打完的点完。
 */
@Data
@Builder
public class TournamentMatchesResponse {

    private Long id;

    private String title;

    private Integer status;

    private String statusText;

    /** 当前登录用户是否管理员 */
    private boolean admin;

    /** 各组别的对阵 */
    private List<FormatMatches> formats;

    /** 单个组别的对阵 */
    @Data
    @Builder
    public static class FormatMatches {

        private int format;

        private String formatName;

        /** 待录入（winner=0），按轮次顺序 */
        private List<TournamentMatchItem> pending;

        /** 已出结果，按轮次倒序 */
        private List<TournamentMatchItem> done;
    }
}