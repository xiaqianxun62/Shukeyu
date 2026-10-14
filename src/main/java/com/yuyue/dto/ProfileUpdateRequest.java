package com.yuyue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 完善 / 更新个人资料：微信用户首次登录后补姓名、性别、账号
 */
@Data
public class ProfileUpdateRequest {

    @Size(max = 32, message = "姓名过长")
    private String name;

    /** 昵称：积分榜等非姓名场景展示；不传(null)表示不修改，传空串表示清除 */
    @Size(max = 32, message = "昵称过长")
    private String nickname;

    /** 积分榜展示名偏好：0 真实姓名 1 昵称；不传表示不修改 */
    @Min(value = 0, message = "展示名取值不合法")
    @Max(value = 1, message = "展示名取值不合法")
    private Integer nameDisplay;

    /** 1男 2女 */
    @NotNull(message = "性别不能为空")
    @Min(value = 1, message = "性别取值不合法")
    @Max(value = 2, message = "性别取值不合法")
    private Integer gender;

    /** 登录账号：留空表示不绑定，非空时做唯一校验 */
    @Size(max = 32, message = "账号过长")
    private String account;

    /** 个人头像 URL，留空表示不修改 */
    @Size(max = 500, message = "头像地址过长")
    private String avatar;
}
