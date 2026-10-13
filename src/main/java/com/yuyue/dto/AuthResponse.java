package com.yuyue.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {

    private String token;

    private Long userId;

    private String name;

    /** 昵称：积分榜等非姓名场景展示，未设置时为 null */
    private String nickname;

    private Integer gender;

    /** 登录账号：绑定后才有，未绑定时为 null */
    private String account;

    /** 个人头像 URL */
    private String avatar;

    private Integer rating;

    private Integer gamesPlayed;

    /** 是否管理员（后端 user.isAdmin） */
    private Boolean isAdmin;

    /** 身份校验是否已通过 */
    private Boolean isVerified;

    /** 是否本次微信登录新建的用户（前端据此引导完善资料） */
    private Boolean newUser;
}
