package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AuthResponse {

    private String token;

    private Long userId;

    private String name;

    /** 昵称：积分榜等非姓名场景展示，未设置时为 null */
    private String nickname;

    /** 积分榜展示名偏好：0 真实姓名 1 昵称（默认） */
    private Integer nameDisplay;

    private Integer gender;

    /** 登录账号：绑定后才有，未绑定时为 null */
    private String account;

    /** 是否已设置登录密码（微信用户从未设置时为 false，前端据此显示「设置密码」而不是「修改密码」） */
    private Boolean hasPassword;

    /** 个人头像 URL */
    private String avatar;

    private Integer rating;

    private Integer gamesPlayed;

    /* ---------- 分类 ELO：单打 / 双打 / 混双（个人页明细展示） ---------- */

    private Integer ratingSingles;

    private Integer singlesWin;

    private Integer singlesLoss;

    private Integer ratingDoubles;

    private Integer doublesWin;

    private Integer doublesLoss;

    private Integer ratingMixed;

    private Integer mixedWin;

    private Integer mixedLoss;

    /** 当前用户的权限码列表（RBAC，四级角色映射；前端据此做管理入口显隐） */
    private List<String> permissions;

    /** 身份校验是否已通过 */
    private Boolean isVerified;

    /** 是否本次微信登录新建的用户（前端据此引导完善资料） */
    private Boolean newUser;
}
