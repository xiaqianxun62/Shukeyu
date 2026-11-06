package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.common.ErrorCode;
import com.yuyue.common.OperationLog;
import com.yuyue.entity.User;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.UserMapper;
import com.yuyue.service.PermissionService;
import com.yuyue.service.UserService;
import com.yuyue.web.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理（持 user:manage 权限者：社长 / 系统管理员）
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final PermissionService permissionService;

    /** 列出所有用户（不返回密码哈希） */
    @GetMapping
    @Operation(summary = "列出所有用户", description = "持 user:manage 权限（社长/系统管理员）")
    public ApiResponse<List<User>> list() {
        permissionService.requirePermission(UserContext.require(), PermissionService.P_USER_MANAGE);
        List<User> users = userMapper.selectList(null);
        users.forEach(u -> u.setPasswordHash(null));
        return ApiResponse.ok(users);
    }

    /** 可分配的角色清单（官网分配界面数据源） */
    @GetMapping("roles")
    @Operation(summary = "可分配角色清单", description = "返回四级角色码与名称，按权限从低到高")
    public ApiResponse<List<PermissionService.RoleView>> listRoles() {
        permissionService.requirePermission(UserContext.require(), PermissionService.P_USER_MANAGE);
        return ApiResponse.ok(permissionService.listAssignableRoles());
    }

    /** 角色 → 权限码矩阵：分配角色前先看清每个角色到底包含哪些权限 */
    @GetMapping("roles/permissions")
    @Operation(summary = "角色权限矩阵",
            description = "返回四级角色及各自包含的权限码（code + 中文名），供管理端展示")
    public ApiResponse<List<PermissionService.RolePermissionView>> rolePermissions() {
        permissionService.requirePermission(UserContext.require(), PermissionService.P_USER_MANAGE);
        return ApiResponse.ok(permissionService.listRolePermissionMatrix());
    }

    /** 查看/重置某人当前角色（供编辑界面回显） */
    @GetMapping("{id}/roles")
    @Operation(summary = "查某人的角色码", description = "持 user:manage 权限")
    public ApiResponse<List<String>> userRoles(@PathVariable Long id) {
        permissionService.requirePermission(UserContext.require(), PermissionService.P_USER_MANAGE);
        return ApiResponse.ok(permissionService.getRoles(id));
    }

    /** 分配角色（整体覆盖式）：干事代管任意球局、社长管理用户，角色码见 GET /users/roles */
    @PutMapping("{id}/roles")
    @Operation(summary = "分配角色（整体覆盖）",
            description = "body: {\"roles\":[\"OFFICER\",\"ADMIN\"]}；不能改自己，ADMIN 仅系统管理员可授，系统至少保留一名 ADMIN")
    @OperationLog("分配角色")
    public ApiResponse<List<String>> assignRoles(@PathVariable Long id,
                                                 @RequestBody AssignRolesRequest req) {
        permissionService.requirePermission(UserContext.require(), PermissionService.P_USER_MANAGE);
        User target = userMapper.selectById(id);
        if (target == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        List<String> roles = permissionService.assignRoles(UserContext.require(), id, req.getRoles());
        return ApiResponse.ok(roles);
    }

    /** 管理员重置某账号的密码为默认密码 123456 */
    @PutMapping("/{id}/reset-password")
    @Operation(summary = "重置账号密码为 123456", description = "持 user:manage 权限；未设置账号的微信用户拒绝")
    @OperationLog("重置密码")
    public ApiResponse<Void> resetPassword(@PathVariable Long id) {
        permissionService.requirePermission(UserContext.require(), PermissionService.P_USER_MANAGE);
        userService.adminResetPassword(id);
        return ApiResponse.ok();
    }

    /** 角色分配请求体 */
    @lombok.Data
    public static class AssignRolesRequest {
        /** 角色码列表，整体覆盖式赋权；空数组 = 降回普通社员 */
        private List<String> roles;
    }
}
