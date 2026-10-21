package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户（账号 + 姓名认证，或微信 openid 登录）
 */
@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号（账号密码登录用；微信用户未绑定时为空） */
    @TableField("`account`")
    private String account;

    /** 微信小程序 openid，未绑定微信时为空 */
    private String wxOpenid;

    /** 姓名 */
    private String name;

    /** 昵称：积分榜等非姓名展示场景使用；为空时回退显示姓名 */
    private String nickname;

    /**
     * 积分榜展示名偏好：0 真实姓名 1 昵称（默认）。
     * 只影响本人在积分榜上的显示，其他人的展示名不受影响。
     */
    @TableField("name_display")
    private Integer nameDisplay;

    /** 0未知 1男 2女 */
    private Integer gender;

    /** 个人头像 URL */
    private String avatar;

    /** 0 普通用户 1 管理员（管理员可改站点文案、管理任何球局） */
    private Integer isAdmin;

    /** 身份校验通过标记 0未通过 1已通过 */
    private Integer isVerified;

    private String passwordHash;

    /** ELO 积分（综合 = 三类分均值，结算时刷新） */
    private Integer rating;

    /** 历史场次 */
    private Integer gamesPlayed;

    private Integer winCount;

    private Integer lossCount;

    /* ---------- 分类 ELO：单打 ---------- */

    private Integer ratingSingles;

    private Integer singlesWin;

    private Integer singlesLoss;

    private Integer singlesPlayed;

    /* ---------- 分类 ELO：同性别双打（男双+女双） ---------- */

    private Integer ratingDoubles;

    private Integer doublesWin;

    private Integer doublesLoss;

    private Integer doublesPlayed;

    /* ---------- 分类 ELO：混双 ---------- */

    private Integer ratingMixed;

    private Integer mixedWin;

    private Integer mixedLoss;

    private Integer mixedPlayed;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
