package com.yuyue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 自助修改密码请求。
 * <p>
 * 长度规则与注册保持一致（6-64 位）；原密码只在「已经设置过密码」时必填，
 * 微信用户首次设置密码可以只传新密码。
 */
@Data
public class ChangePasswordRequest {

    /** 原密码：已设置过密码的账号必填，微信用户首次设置可留空 */
    @Size(max = 64, message = "原密码过长")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度需为 6-64 位")
    private String newPassword;
}
