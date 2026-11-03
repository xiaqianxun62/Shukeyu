package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对局（一场比赛：单打 / 男双 / 女双 / 混双）
 */
@Data
@TableName("match_game")
public class MatchGame {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属球局 */
    private Long gameId;

    /** 1单打 2男双 3女双 4混双 */
    private Integer format;

    /** 轮排轮次：0 表示不是轮排生成的对阵 */
    private Integer roundNo = 0;

    /** 轮排场地号，从 1 开始；0 表示不是轮排生成的对阵 */
    private Integer court = 0;

    /** A队成员id，逗号分隔（1个=单打，2个=双打） */
    private String teamA;

    /** B队成员id，逗号分隔 */
    private String teamB;

    private Integer scoreA;

    private Integer scoreB;

    /** 0未定 1A队 2B队 */
    private Integer winner;

    /** 0未结算 1已结算 */
    private Integer settleStatus;

    private LocalDateTime createTime;

    private LocalDateTime settleTime;

    @TableLogic
    private Integer deleted;
}
