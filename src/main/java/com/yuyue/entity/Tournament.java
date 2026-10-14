package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 大型比赛赛事主体。
 *
 * <p>一个赛事可同时开设多个组别（{@link #formatList}，如 "1,2,3,4"），
 * 每个组别独立分组、独立产出名次；小组规模与晋级名额由管理员创建时配置。
 *
 * <p>本模块不接ELO：不写 rating_history、不动 user.rating。
 */
@Data
@TableName("tournament")
public class Tournament {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 赛事名称 */
    private String title;

    /** 比赛地点 */
    private String location;

    /** 关联球场字典（court 表，可空） */
    private Long courtId;

    /** 比赛日期 */
    private LocalDate playDate;

    /** 赛事说明 / 补充规则 */
    private String remark;

    /** 开设组别，逗号分隔：1单打 2男双 3女双 4混双 */
    private String formatList;

    /** 小组赛每组队数（组内单循环） */
    private Integer groupSize;

    /** 每组晋级名额 */
    private Integer advancePerGroup;

    /** 单个组别的参赛队数上限 */
    private Integer maxEntries;

    /** 0报名中 1已抽签 2进行中 3已结束 4已取消 */
    private Integer status;

    /** 冠军参赛单位 */
    private Long championEntryId;

    /** 亚军参赛单位 */
    private Long runnerUpEntryId;

    /** 季军参赛单位 */
    private Long thirdEntryId;

    /** 第 4 名（季军赛负者） */
    private Long fourthEntryId;

    /** 创建人（管理员） */
    private Long creatorId;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}