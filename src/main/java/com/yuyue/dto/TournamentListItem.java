package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 赛事列表项（赛事广场）
 */
@Data
@Builder
public class TournamentListItem {

    private Long id;

    private String title;

    private String location;

    private LocalDate playDate;

    /** 0 报名中 1 已抽签 2 进行中 3 已结束 4 已取消 */
    private Integer status;

    private String statusText;

    /** 开设组别 code 列表，如 [1, 2, 4] */
    private List<Integer> formats;

    /** 开设组别名列表，如 [单打, 男双, 混双] */
    private List<String> formatNames;

    /** 已报名参赛单位总数（跨所有组别） */
    private int entryCount;

    /** 单个组别的参赛上限 */
    private int maxEntries;

    /** 参赛人数上限合计（组队赛按人数算，双打 ×2） */
    private int maxPeople;

    /** 当前登录用户是否已报名本赛事 */
    private boolean joined;

    /** 已出冠军时返回冠军队名 */
    private String championName;
}