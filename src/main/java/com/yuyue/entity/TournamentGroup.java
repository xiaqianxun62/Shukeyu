package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 比赛小组（随机抽签分组后生成，一个小组只属于一个组别）
 */
@Data
@TableName("tournament_group")
public class TournamentGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属赛事 */
    private Long tournamentId;

    /** 1单打 2男双 3女双 4混双 */
    private Integer format;

    /** 组名 A / B / C... */
    private String groupNo;

    /** 组序号，从 1 开始，用于排序与展示 */
    private Integer seq;

    /** 组内实际参赛队数（最后一组可能不满） */
    private Integer entryCount;

    /** 0未开始 1进行中 2已完赛 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}