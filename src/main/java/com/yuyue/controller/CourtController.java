package com.yuyue.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.ApiResponse;
import com.yuyue.common.ErrorCode;
import com.yuyue.common.OperationLog;
import com.yuyue.dto.CourtAdminView;
import com.yuyue.dto.CourtRequest;
import com.yuyue.entity.Court;
import com.yuyue.entity.Game;
import com.yuyue.entity.Tournament;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.CourtMapper;
import com.yuyue.mapper.GameMapper;
import com.yuyue.mapper.TournamentMapper;
import com.yuyue.service.PermissionService;
import com.yuyue.web.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 球场场地字典：
 * - GET /courts        公开接口，前端下拉用（只返回启用的，按 sort 排序）
 * - POST/PUT/DELETE    持 court:manage 权限者（管理员）
 */
@Tag(name = "球场场地")
@RestController
@RequestMapping("/courts")
@RequiredArgsConstructor
public class CourtController {

    private final CourtMapper courtMapper;
    private final GameMapper gameMapper;
    private final TournamentMapper tournamentMapper;
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

    /** 管理员：列出全部球场（含停用的），并带上被球局 / 赛事引用的次数 */
    @GetMapping("/admin")
    @Operation(summary = "管理员：列出全部球场（含停用），附引用计数",
            description = "gameCount / tournamentCount > 0 表示被业务数据引用，此时只能停用不能删除")
    public ApiResponse<List<CourtAdminView>> adminList() {
        requireAdmin();
        List<Court> courts = courtMapper.selectList(new LambdaQueryWrapper<Court>()
                .orderByAsc(Court::getSort)
                .orderByAsc(Court::getId));
        if (courts.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        List<Long> ids = courts.stream().map(Court::getId).toList();

        // 只在应用层聚合计数，避免「每个球场查两次」的 N+1
        Map<Long, Long> gameCounts = countRefs(gameMapper.selectList(new LambdaQueryWrapper<Game>()
                .select(Game::getCourtId)
                .in(Game::getCourtId, ids)), Game::getCourtId);
        Map<Long, Long> tournamentCounts = countRefs(tournamentMapper.selectList(
                new LambdaQueryWrapper<Tournament>()
                        .select(Tournament::getCourtId)
                        .in(Tournament::getCourtId, ids)), Tournament::getCourtId);

        List<CourtAdminView> views = courts.stream()
                .map(c -> CourtAdminView.of(c,
                        gameCounts.getOrDefault(c.getId(), 0L),
                        tournamentCounts.getOrDefault(c.getId(), 0L)))
                .toList();
        return ApiResponse.ok(views);
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
        insertIgnoreDuplicate(c, req.getName());
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
        try {
            courtMapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            // 老库上唯一键可能还在（迁移未跑完），给一句人话而不是 500
            throw new BizException(ErrorCode.PARAM_ERROR, "球场名称已存在：" + req.getName());
        }
        return ApiResponse.ok(existing);
    }

    /**
     * 管理员：删除（逻辑删）
     * <p>
     * 被球局 / 赛事引用时拒绝删除：逻辑删除后 court 行查不到，历史球局会丢场地名与经纬度。
     * 这类场景应「停用」（enabled=0），停用后不再出现在发布下拉里，但历史数据照常可读。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "管理员：删除球场（被引用时拒绝，请改用停用）")
    @OperationLog("删除球场")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requireAdmin();
        Court existing = courtMapper.selectById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "球场不存在");
        }
        long games = gameMapper.selectCount(new LambdaQueryWrapper<Game>().eq(Game::getCourtId, id));
        long tournaments = tournamentMapper.selectCount(
                new LambdaQueryWrapper<Tournament>().eq(Tournament::getCourtId, id));
        if (games > 0 || tournaments > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该球场仍被 " + games + " 个球局、" + tournaments + " 个赛事使用；"
                            + "删除会让这些记录的场地名与定位失效，请改为「停用」");
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

    /** 入库失败兜底：唯一键冲突（并发新增 / 旧库索引未更新）转成可读的业务错误 */
    private void insertIgnoreDuplicate(Court c, String name) {
        try {
            courtMapper.insert(c);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "球场名称已存在：" + name);
        }
    }

    /** 同名校验：只比对未删除的场地（逻辑删除的历史行不占用名字） */
    private void checkNameUnique(Long excludeId, String name) {
        Court exist = courtMapper.selectOne(new LambdaQueryWrapper<Court>()
                .eq(Court::getName, name.trim())
                .last("limit 1"));
        if (exist != null && !exist.getId().equals(excludeId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "球场名称已存在：" + name);
        }
    }

    /** 按 courtId 聚合引用次数 */
    private <T> Map<Long, Long> countRefs(List<T> rows, Function<T, Long> courtIdGetter) {
        Map<Long, Long> counts = new HashMap<>();
        for (T row : rows) {
            Long courtId = courtIdGetter.apply(row);
            if (courtId != null) {
                counts.merge(courtId, 1L, Long::sum);
            }
        }
        return counts;
    }

    /** 持 court:manage 权限（ADMIN）方可维护场地字典 */
    private void requireAdmin() {
        permissionService.requirePermission(UserContext.require(), PermissionService.P_COURT_MANAGE);
    }
}
