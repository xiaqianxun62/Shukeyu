package com.yuyue.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.ApiResponse;
import com.yuyue.common.ErrorCode;
import com.yuyue.common.OperationLog;
import com.yuyue.dto.CourtRequest;
import com.yuyue.entity.Court;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.CourtMapper;
import com.yuyue.service.PermissionService;
import com.yuyue.web.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 球场场地字典：
 * - GET /courts        公开接口，前端下拉用（只返回启用的，按 sort 排序）
 * - POST/PUT/DELETE    管理员专属
 */
@Tag(name = "球场场地")
@RestController
@RequestMapping("/courts")
@RequiredArgsConstructor
public class CourtController {

    private final CourtMapper courtMapper;
    private final PermissionService permissionService;

    /** 公开：列出所有启用的球场（按 sort 排序，下拉框数据源） */
    @GetMapping
    @Operation(summary = "列出启用的球场（公开）")
    public ApiResponse<List<Court>> list() {
        return ApiResponse.ok(courtMapper.selectList(new LambdaQueryWrapper<Court>()
                .eq(Court::getEnabled, 1)
                .orderByAsc(Court::getSort)
                .orderByAsc(Court::getId)));
    }

    /** 管理员：列出全部球场（含停用的） */
    @GetMapping("/admin")
    @Operation(summary = "管理员：列出全部球场（含停用）")
    public ApiResponse<List<Court>> adminList() {
        requireAdmin();
        return ApiResponse.ok(courtMapper.selectList(new LambdaQueryWrapper<Court>()
                .orderByAsc(Court::getSort)
                .orderByAsc(Court::getId)));
    }

    /** 管理员：新增 */
    @PostMapping
    @Operation(summary = "管理员：新增球场")
    @OperationLog("新增球场")
    public ApiResponse<Court> create(@RequestBody @Valid CourtRequest req) {
        requireAdmin();
        checkNameUnique(null, req.getName());
        Court c = new Court();
        applyRequest(c, req);
        if (c.getSort() == null) c.setSort(0);
        if (c.getEnabled() == null) c.setEnabled(1);
        courtMapper.insert(c);
        return ApiResponse.ok(c);
    }

    /** 管理员：编辑 */
    @PutMapping("/{id}")
    @Operation(summary = "管理员：编辑球场")
    @OperationLog("编辑球场")
    public ApiResponse<Court> update(@PathVariable Long id, @RequestBody @Valid CourtRequest req) {
        requireAdmin();
        Court existing = courtMapper.selectById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "球场不存在");
        }
        checkNameUnique(id, req.getName());
        applyRequest(existing, req);
        courtMapper.updateById(existing);
        return ApiResponse.ok(existing);
    }

    /** 管理员：删除（逻辑删） */
    @DeleteMapping("/{id}")
    @Operation(summary = "管理员：删除球场")
    @OperationLog("删除球场")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requireAdmin();
        Court existing = courtMapper.selectById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "球场不存在");
        }
        courtMapper.deleteById(id);
        return ApiResponse.ok(null);
    }

    /** 管理员：切换启用 / 停用 */
    @PutMapping("/{id}/toggle")
    @Operation(summary = "管理员：启用/停用球场")
    @OperationLog("切换球场状态")
    public ApiResponse<Court> toggle(@PathVariable Long id) {
        requireAdmin();
        Court existing = courtMapper.selectById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "球场不存在");
        }
        existing.setEnabled(existing.getEnabled() == 1 ? 0 : 1);
        courtMapper.updateById(existing);
        return ApiResponse.ok(existing);
    }

    /* ---- helpers ---- */

    private void applyRequest(Court c, CourtRequest req) {
        c.setName(req.getName().trim());
        c.setAddress(req.getAddress() == null ? null : req.getAddress().trim());
        c.setLat(req.getLat());
        c.setLng(req.getLng());
        if (req.getSort() != null) c.setSort(req.getSort());
        if (req.getEnabled() != null) c.setEnabled(req.getEnabled());
    }

    /** 同名校验：忽略自己 */
    private void checkNameUnique(Long excludeId, String name) {
        Court exist = courtMapper.selectOne(new LambdaQueryWrapper<Court>()
                .eq(Court::getName, name.trim())
                .last("limit 1"));
        if (exist != null && !exist.getId().equals(excludeId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "球场名称已存在：" + name);
        }
    }

    /** 持 court:manage 权限（ADMIN）方可维护场地字典 */
    private void requireAdmin() {
        permissionService.requirePermission(UserContext.require(), PermissionService.P_COURT_MANAGE);
    }
}
