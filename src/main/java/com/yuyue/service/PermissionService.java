package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.entity.Role;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.PermissionMapper;
import com.yuyue.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RBAC 权限中心：全后端唯一一处权限判定与角色分配出口。
 *
 * <p>权限码逐请求走 Redis 缓存（{@link Constants#RBAC_PERM_CACHE}，
 * TTL 24h，角色变更时按用户失效），避免每个接口都连三次表。
 *
 * <p>四级角色体系（user_role / role_permission / role / permission 四表）：
 * MEMBER 社员（隐式默认，无管理码）→ OFFICER 干事 → PRESIDENT 社长 → ADMIN 系统管理员。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    /* ---------- 权限码常量：业务代码只认这些，拼写改这里一处 ---------- */
    /** 球局管理：代管任意球局（编排 / 计分 / 隐藏 / 编辑 / 删除） */
    public static final String P_GAME_MANAGE = "game:manage";
    /** 轮排管理：生成轮排表 / 录分（发起人以外） */
    public static final String P_ROTATION_MANAGE = "rotation:manage";
    /** 赛事管理：创建 / 抽签 / 取消 / 移出选手 */
    public static final String P_TOURNAMENT_MANAGE = "tournament:manage";
    /** 场地字典维护 */
    public static final String P_COURT_MANAGE = "court:manage";
    /** 站点配置（首页诗句 / 弹窗公告） */
    public static final String P_SETTINGS_MANAGE = "settings:manage";
    /** 用户与角色分配 */
    public static final String P_USER_MANAGE = "user:manage";
    /** SHUKE 认证与题库管理 */
    public static final String P_VERIFY_MANAGE = "verify:manage";
    /** 访问日志查看 */
    public static final String P_LOG_VIEW = "log:view";

    /** 官网分发角色界面可选择的全部角色（按权限从低到高） */
    public static final List<String> ASSIGNABLE_ROLES = List.of("MEMBER", "OFFICER", "PRESIDENT", "ADMIN");

    /** Redis 权限缓存 TTL */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final StringRedisTemplate redisTemplate;

    /* ==================== 判定 ==================== */

    /** 是否持有权限码（结果读缓存，未命中回源 DB） */
    public boolean hasPermission(Long userId, String code) {
        if (userId == null) {
            return false;
        }
        String csv = redisTemplate.opsForValue().get(cacheKey(userId));
        List<String> codes = csv == null ? loadPermissions(userId) : split(csv);
        return codes.contains(code);
    }

    /** 必须持有权限码，否则 FORBIDDEN（controller 管理接口统一用这个） */
    public void requirePermission(Long userId, String code) {
        if (!hasPermission(userId, code)) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少权限：" + code);
        }
    }

    /** 持有任一权限码即通过 */
    public boolean hasAnyPermission(Long userId, String... codes) {
        if (userId == null || codes.length == 0) {
            return false;
        }
        String csv = redisTemplate.opsForValue().get(cacheKey(userId));
        List<String> own = csv == null ? loadPermissions(userId) : split(csv);
        for (String code : codes) {
            if (own.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /** 用户全部权限码（供登录响应下发前端做入口显隐） */
    public List<String> getPermissions(Long userId) {
        String csv = redisTemplate.opsForValue().get(cacheKey(userId));
        return csv == null ? loadPermissions(userId) : split(csv);
    }

    /** 用户全部角色编码（高权限排前） */
    public List<String> getRoles(Long userId) {
        return permissionMapper.selectRoleCodesByUserId(userId);
    }

    /* ==================== 分配 ==================== */

    /**
     * 分配角色（整体覆盖式）：把 target 的角色替换为 roleCodes。
     * 规则：
     *  1) 操作者不能改自己的角色（防止自我提权 / 末位管理员自锁）；
     *  2) 别人是 ADMIN 时，只有 ADMIN 能改他——否则持 user:manage 的社长可以把管理员
     *     降成社员（只要还剩另一名管理员就能绕过末位保护），等于以下犯上；
     *  3) ADMIN 角色只有 ADMIN 才能授出；
     *  4) 系统必须至少保留一名 ADMIN（末位管理员保护）。
     */
    @Transactional
    public List<String> assignRoles(Long operatorId, Long targetUserId, List<String> roleCodes) {
        if (operatorId.equals(targetUserId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不能修改自己的角色（防止末位管理员自锁）");
        }
        boolean operatorIsAdmin = hasRole(operatorId, "ADMIN");
        if (hasRole(targetUserId, "ADMIN") && !operatorIsAdmin) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有系统管理员可以变更系统管理员的角色");
        }
        List<String> distinct = new ArrayList<>(new HashSet<>(roleCodes == null ? List.of() : roleCodes));
        for (String code : distinct) {
            if (!ASSIGNABLE_ROLES.contains(code)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "未知角色：" + code);
            }
        }
        boolean grantsAdmin = distinct.contains("ADMIN");
        if (grantsAdmin && !(hasPermission(operatorId, P_USER_MANAGE) && operatorIsAdmin)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅系统管理员可分配 ADMIN 角色");
        }
        // 目标此前是 ADMIN 且新角色集中没有 ADMIN → 视为撤销，触发末位保护
        boolean revokesAdmin = hasRole(targetUserId, "ADMIN") && !grantsAdmin;
        if (revokesAdmin && permissionMapper.countAdminHolders() <= 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "系统至少需保留一名系统管理员");
        }

        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>().in(Role::getCode, distinct));
        List<Long> keepIds = roles.stream().map(Role::getId).toList();

        // 整体覆盖：先清空再插
        List<Role> existed = roleMapper.selectList(null);
        for (Role r : existed) {
            roleMapper.deleteUserRole(targetUserId, r.getId());
        }
        for (Long id : keepIds) {
            roleMapper.insertUserRole(targetUserId, id);
        }

        invalidate(targetUserId);
        List<String> assigned = keepIds.isEmpty() ? List.of() : getRoles(targetUserId);
        log.info("角色分配完成: operator={}, target={}, roles={}", operatorId, targetUserId, assigned);
        return assigned;
    }

    /* ==================== 角色/权限视图 ==================== */

    /** 可选角色视图（官网分配界面数据源） */
    public record RoleView(String code, String name) {}

    /** 四级角色清单（按权限从低到高，与 ASSIGNABLE_ROLES 同序） */
    public List<RoleView> listAssignableRoles() {
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .in(Role::getCode, ASSIGNABLE_ROLES));
        return roles.stream()
                .sorted(java.util.Comparator.comparingInt(r -> ASSIGNABLE_ROLES.indexOf(r.getCode())))
                .map(r -> new RoleView(r.getCode(), r.getName()))
                .toList();
    }

    /** 权限码视图：code 供接口判定，name 供管理端展示 */
    public record PermissionView(String code, String name) {}

    /** 角色 → 权限码视图（管理端分配角色时显示「勾上这个角色到底给了什么」） */
    public record RolePermissionView(String code, String name, List<PermissionView> permissions) {}

    /**
     * 角色权限矩阵：每个可分配角色包含哪些权限码。
     * <p>
     * 只读、数据量固定（4 个角色 × 不超过 8 个权限码），管理端每次打开分配弹窗拉一次即可，
     * 不做缓存——改了 role_permission 之后刷新就能看到最新结果。
     */
    public List<RolePermissionView> listRolePermissionMatrix() {
        return listAssignableRoles().stream()
                .map(role -> new RolePermissionView(role.code(), role.name(),
                        permissionMapper.selectPermissionsByRoleCode(role.code()).stream()
                                .map(p -> new PermissionView(p.getCode(), p.getName()))
                                .toList()))
                .toList();
    }

    /* ==================== 缓存 ==================== */

    /** 角色分配 / 认证变更后失效单用户缓存 */
    public void invalidate(Long userId) {
        redisTemplate.delete(cacheKey(userId));
    }

    /** 权限码表手工调整后可整体失效（低频操作，浅实现：删所有前缀 key 由运维手动处理亦可） */
    public void invalidateAll() {
        Set<String> keys = redisTemplate.keys(Constants.RBAC_PERM_CACHE + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /* ==================== 内部 ==================== */

    /** 回源 DB 并写缓存（空权限也缓存为空串，避免 MEMBER 每请求查库） */
    private List<String> loadPermissions(Long userId) {
        List<String> codes = permissionMapper.selectPermissionCodesByUserId(userId);
        redisTemplate.opsForValue().set(cacheKey(userId), String.join(",", codes), CACHE_TTL);
        log.debug("权限缓存回源: userId={}, codes={}", userId, codes);
        return codes;
    }

    private boolean hasRole(Long userId, String code) {
        return getRoles(userId).contains(code);
    }

    private static String cacheKey(Long userId) {
        return Constants.RBAC_PERM_CACHE + userId;
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }
}
