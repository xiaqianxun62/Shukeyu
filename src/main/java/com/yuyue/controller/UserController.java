package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.common.OperationLog;
import com.yuyue.entity.User;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.UserMapper;
import com.yuyue.service.UserService;
import com.yuyue.web.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理（管理员功能：列出所有用户、设置/取消管理员身份）
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    /** 管理员列出所有用户（不返回密码哈希） */
    @GetMapping
    @Operation(summary = "管理员：列出所有用户")
    public ApiResponse<List<User>> list() {
        Long operatorId = UserContext.require();
        requireAdmin(operatorId);
        List<User> users = userMapper.selectList(null);
        users.forEach(u -> u.setPasswordHash(null));
        return ApiResponse.ok(users);
    }

    /** 管理员设置或取消某人的 isAdmin */
    @PutMapping("/{id}/admin")
    @Operation(summary = "管理员：设置/取消管理员身份")
    @OperationLog("设置管理员")
    public ApiResponse<User> setAdmin(@PathVariable Long id, @RequestParam boolean admin) {
        Long operatorId = UserContext.require();
        requireAdmin(operatorId);
        if (operatorId.equals(id)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不能设置自己");
        }
        User target = userMapper.selectById(id);
        if (target == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        target.setIsAdmin(admin ? Constants.ADMIN_YES : Constants.ADMIN_NO);
        userMapper.updateById(target);
        target.setPasswordHash(null);
        return ApiResponse.ok(target);
    }

    /** 管理员重置某账号的密码为默认密码 123456 */
    @PutMapping("/{id}/reset-password")
    @Operation(summary = "管理员：重置账号密码为 123456")
    @OperationLog("重置密码")
    public ApiResponse<Void> resetPassword(@PathVariable Long id) {
        Long operatorId = UserContext.require();
        requireAdmin(operatorId);
        userService.adminResetPassword(id);
        return ApiResponse.ok();
    }

    private void requireAdmin(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null || u.getIsAdmin() == null || u.getIsAdmin() != 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可操作");
        }
    }
}
