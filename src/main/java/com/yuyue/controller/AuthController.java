package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.common.ErrorCode;
import com.yuyue.common.OperationLog;
import com.yuyue.dto.AuthResponse;
import com.yuyue.dto.ChangePasswordRequest;
import com.yuyue.dto.LoginRequest;
import com.yuyue.dto.ProfileUpdateRequest;
import com.yuyue.dto.RegisterRequest;
import com.yuyue.dto.WxLoginRequest;
import com.yuyue.exception.BizException;
import com.yuyue.service.CaptchaService;
import com.yuyue.service.UserService;
import com.yuyue.web.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 认证：注册 / 登录 / 我的信息
 */
@RestController
@RequestMapping("auth")
@RequiredArgsConstructor
@Tag(name = "用户", description = "小程序用户 / 登录 / 我的信息")
public class AuthController {

    private final UserService userService;
    private final CaptchaService captchaService;

    /** 图形验证码：返回 { uuid, imageBase64 }，登录/注册必填 */
    @Operation(summary = "获取图形验证码")
    @GetMapping("captcha")
    public ApiResponse<Map<String, String>> captcha() {
        CaptchaService.CaptchaIssue issue = captchaService.issue();
        return ApiResponse.ok(Map.of("uuid", issue.uuid(), "image", issue.imageBase64()));
    }

    @Operation(summary = "昵称查重", description = "公开接口，注册/编辑资料前预先校验昵称是否可用")
    @GetMapping("name-check")
    public ApiResponse<Map<String, Object>> nameCheck(
            @RequestParam String name,
            @RequestParam(required = false) Long excludeId) {
        boolean available = userService.nameAvailable(name, excludeId);
        return ApiResponse.ok(Map.of("available", available));
    }

    @Operation(summary = "注册", description = "账号 + 姓名 + 图形验证码注册，成功后直接返回 JWT")
    @SecurityRequirements
    @PostMapping("register")
    @OperationLog("用户注册")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        verifyCaptcha(req.getCaptchaUuid(), req.getCaptchaCode());
        return ApiResponse.ok(userService.register(req));
    }

    @Operation(summary = "登录", description = "账号 + 密码 + 图形验证码登录，返回 JWT")
    @SecurityRequirements
    @PostMapping("login")
    @OperationLog("账号登录")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        verifyCaptcha(req.getCaptchaUuid(), req.getCaptchaCode());
        return ApiResponse.ok(userService.login(req));
    }

    private void verifyCaptcha(String uuid, String code) {
        if (!captchaService.verify(uuid, code)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "图形验证码错误或已过期");
        }
    }

    @Operation(summary = "微信小程序登录", description = "uni.login() 拿到 code 后换 openid 登录，首次登录自动注册")
    @SecurityRequirements
    @PostMapping("wxlogin")
    @OperationLog("微信登录")
    public ApiResponse<AuthResponse> wxLogin(@Valid @RequestBody WxLoginRequest req) {
        return ApiResponse.ok(userService.wxLogin(req));
    }

    @Operation(summary = "完善资料", description = "微信用户补充姓名 / 性别 / 账号")
    @PostMapping("profile")
    @OperationLog("完善个人资料")
    public ApiResponse<AuthResponse> profile(@Valid @RequestBody ProfileUpdateRequest req) {
        return ApiResponse.ok(userService.updateProfile(UserContext.require(), req));
    }

    /** 编辑个人信息：与 POST /auth/profile 同逻辑，REST 语义上是「整体更新资料」 */
    @Operation(summary = "编辑个人信息",
            description = "修改姓名 / 性别 / 账号，字段留空表示不修改；账号做唯一校验")
    @PutMapping("profile")
    @OperationLog("编辑个人资料")
    public ApiResponse<AuthResponse> updateProfile(@Valid @RequestBody ProfileUpdateRequest req) {
        return ApiResponse.ok(userService.updateProfile(UserContext.require(), req));
    }

    @Operation(summary = "修改密码",
            description = "已设置密码需校验原密码；微信用户首次设置只需新密码（需先绑定账号）。成功后换发新 token，其他设备旧会话失效")
    @PutMapping("password")
    @OperationLog(value = "修改密码", logArgs = false)
    public ApiResponse<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        return ApiResponse.ok(userService.changePassword(UserContext.require(), req));
    }

    @Operation(summary = "我的信息", description = "需携带 Authorization: Bearer <token>")
    @GetMapping("me")
    public ApiResponse<AuthResponse> me() {
        return ApiResponse.ok(userService.me(UserContext.require()));
    }

    @Operation(summary = "登出", description = "把当前 token 加入黑名单，使其立即失效")
    @PostMapping("logout")
    @OperationLog(value = "退出登录", logArgs = false)
    public ApiResponse<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        userService.logout(authorization);
        return ApiResponse.ok();
    }

    @Operation(summary = "上传个人头像", description = "multipart 上传图片，返回可访问的头像 URL")
    @PostMapping("avatar")
    @OperationLog("上传个人头像")
    public ApiResponse<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        String url = userService.uploadAvatar(UserContext.require(), file);
        return ApiResponse.ok(Map.of("avatar", url));
    }
}
