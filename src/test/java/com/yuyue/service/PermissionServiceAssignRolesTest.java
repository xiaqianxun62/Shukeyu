package com.yuyue.service;

import com.yuyue.common.ErrorCode;
import com.yuyue.entity.Permission;
import com.yuyue.entity.Role;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.PermissionMapper;
import com.yuyue.mapper.RoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 角色分配的四条硬约束：
 * 1) 不能改自己（防自我提权 / 末位管理员自锁）；
 * 2) 目标是 ADMIN 时只有 ADMIN 能改（否则社长可以「以下犯上」降级管理员）；
 * 3) ADMIN 角色只有 ADMIN 能授出；
 * 4) 系统至少保留一名 ADMIN。
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceAssignRolesTest {

    @Mock private RoleMapper roleMapper;
    @Mock private PermissionMapper permissionMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private PermissionService service;

    @BeforeEach
    void setUp() {
        service = new PermissionService(roleMapper, permissionMapper, redisTemplate);
    }

    private Role role(long id, String code, String name) {
        Role r = new Role();
        r.setId(id);
        r.setCode(code);
        r.setName(name);
        r.setSort(0);
        return r;
    }

    private Permission perm(String code, String name) {
        Permission p = new Permission();
        p.setCode(code);
        p.setName(name);
        return p;
    }

    @Test
    @DisplayName("不能修改自己的角色：PARAM_ERROR，且不写 user_role")
    void rejectsSelfAssignment() {
        BizException e = assertThrows(BizException.class,
                () -> service.assignRoles(1L, 1L, List.of("ADMIN")));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        verifyNoInteractions(roleMapper, permissionMapper);
    }

    @Test
    @DisplayName("社长不能变更管理员的角色（只有 ADMIN 可以）")
    void presidentCannotTouchAdmin() {
        when(permissionMapper.selectRoleCodesByUserId(5L)).thenReturn(List.of("PRESIDENT"));
        when(permissionMapper.selectRoleCodesByUserId(6L)).thenReturn(List.of("ADMIN"));

        BizException e = assertThrows(BizException.class,
                () -> service.assignRoles(5L, 6L, List.of("MEMBER")));

        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("系统管理员"), "实际=" + e.getMessage());
        verify(roleMapper, never()).deleteUserRole(anyLong(), anyLong());
        verify(roleMapper, never()).insertUserRole(anyLong(), anyLong());
    }

    @Test
    @DisplayName("社长不能授出 ADMIN 角色")
    void presidentCannotGrantAdmin() {
        when(permissionMapper.selectRoleCodesByUserId(5L)).thenReturn(List.of("PRESIDENT"));
        when(permissionMapper.selectRoleCodesByUserId(7L)).thenReturn(List.of("OFFICER"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(permissionMapper.selectPermissionCodesByUserId(5L)).thenReturn(List.of("user:manage"));

        BizException e = assertThrows(BizException.class,
                () -> service.assignRoles(5L, 7L, List.of("ADMIN")));

        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("仅系统管理员"), "实际=" + e.getMessage());
        verify(roleMapper, never()).insertUserRole(anyLong(), anyLong());
    }

    @Test
    @DisplayName("末位管理员保护：撤销最后一名 ADMIN 被拒绝")
    void keepsLastAdmin() {
        when(permissionMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("ADMIN"));
        when(permissionMapper.selectRoleCodesByUserId(2L)).thenReturn(List.of("ADMIN"));
        when(permissionMapper.countAdminHolders()).thenReturn(1L);

        BizException e = assertThrows(BizException.class,
                () -> service.assignRoles(1L, 2L, List.of("OFFICER")));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("至少需保留"), "实际=" + e.getMessage());
        verify(roleMapper, never()).insertUserRole(anyLong(), anyLong());
    }

    @Test
    @DisplayName("管理员把社员提为干事：整体覆盖写入并失效权限缓存")
    void adminGrantsOfficer() {
        when(permissionMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("ADMIN"));
        when(permissionMapper.selectRoleCodesByUserId(3L)).thenReturn(List.of(), List.of("OFFICER"));
        when(roleMapper.selectList(any())).thenReturn(List.of(role(2L, "OFFICER", "社团干事")));

        List<String> assigned = service.assignRoles(1L, 3L, List.of("OFFICER", "OFFICER"));

        assertEquals(List.of("OFFICER"), assigned);
        verify(roleMapper).deleteUserRole(3L, 2L);
        verify(roleMapper).insertUserRole(3L, 2L);
        verify(redisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("角色权限矩阵：按四级角色顺序返回各自权限码与中文名")
    void buildsRolePermissionMatrix() {
        when(roleMapper.selectList(any())).thenReturn(List.of(
                role(1L, "MEMBER", "普通社员"),
                role(2L, "OFFICER", "社团干事"),
                role(3L, "PRESIDENT", "社团社长"),
                role(4L, "ADMIN", "系统管理员")));
        when(permissionMapper.selectPermissionsByRoleCode("MEMBER")).thenReturn(List.of());
        when(permissionMapper.selectPermissionsByRoleCode("OFFICER"))
                .thenReturn(List.of(perm("game:manage", "球局管理")));
        when(permissionMapper.selectPermissionsByRoleCode("PRESIDENT"))
                .thenReturn(List.of(perm("game:manage", "球局管理"), perm("user:manage", "用户与角色分配")));
        when(permissionMapper.selectPermissionsByRoleCode("ADMIN"))
                .thenReturn(List.of(perm("court:manage", "场地字典维护")));

        List<PermissionService.RolePermissionView> matrix = service.listRolePermissionMatrix();

        assertEquals(List.of("MEMBER", "OFFICER", "PRESIDENT", "ADMIN"),
                matrix.stream().map(PermissionService.RolePermissionView::code).toList());
        assertEquals(0, matrix.get(0).permissions().size());
        assertEquals("用户与角色分配", matrix.get(2).permissions().get(1).name());
        assertEquals("court:manage", matrix.get(3).permissions().get(0).code());
    }
}
