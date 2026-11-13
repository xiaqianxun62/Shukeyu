package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.common.ErrorCode;
import com.yuyue.common.OperationLog;
import com.yuyue.config.UploadProperties;
import com.yuyue.dto.ArrangeRequest;
import com.yuyue.dto.ArrangeResponse;
import com.yuyue.dto.GameCreateRequest;
import com.yuyue.dto.GameMatchesResponse;
import com.yuyue.dto.GameRegisterRequest;
import com.yuyue.dto.GameResponse;
import com.yuyue.exception.BizException;
import com.yuyue.service.GameService;
import com.yuyue.service.PermissionService;
import com.yuyue.web.UserContext;
import com.yuyue.util.ImageUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 球局：发布 / 列表 / 详情 / 报名 / 自动编排
 */
@RestController
@RequestMapping("games")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "球局", description = "发布 / 列表 / 详情 / 报名 / 自动编排")
public class GameController {

    private final GameService gameService;
    private final UploadProperties uploadProperties;
    private final PermissionService permissionService;

    private static final java.util.Set<String> COVER_EXT_WHITELIST =
            java.util.Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Random RANDOM = new Random();

    /**
     * 上传球局封面图：通用图片上传，仅做类型/大小/扩展名校验 + 落盘，
     * 返回可公开访问的 /uploads/xxx URL。前端拿到 URL 后传给 create 端点。
     * 不走 UserService.uploadAvatar（那个会写 user.avatar 字段）。
     */
    @Operation(summary = "上传球局封面", description = "multipart/form-data，文件字段名 file；返回 /uploads/xxx 可公开访问 URL")
    @PostMapping("upload-cover")
    public ApiResponse<String> uploadCover(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail(ErrorCode.PARAM_ERROR, "封面文件不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ApiResponse.fail(ErrorCode.PARAM_ERROR, "仅支持图片格式");
        }
        long size = file.getSize();
        if (size > uploadProperties.getMaxSize()) {
            return ApiResponse.fail(ErrorCode.PARAM_ERROR, "文件大小超过限制");
        }
        String ext = pickExt(file.getOriginalFilename());
        String fileName = "cover_" + System.currentTimeMillis() + "_" + RANDOM.nextInt(10000) + "." + ext;
        Path dir = Paths.get(uploadProperties.getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName).normalize();
            if (!target.startsWith(dir)) {
                return ApiResponse.fail(ErrorCode.PARAM_ERROR, "非法文件名");
            }
            file.transferTo(target.toFile());

            // 生成缩略图（800×800 JPEG），失败不阻断主流程
            try {
                ImageUtils.generateThumbnail(target, dir.resolve(ImageUtils.thumbFileName(fileName)),
                        ImageUtils.COVER_THUMB_SIZE, ImageUtils.COVER_THUMB_SIZE,
                        contentType, file.getOriginalFilename());
            } catch (Exception e) {
                log.warn("封面缩略图生成失败（不影响主流程）: {}", fileName, e);
            }
        } catch (IOException e) {
            return ApiResponse.fail(ErrorCode.SYSTEM_ERROR, "封面保存失败");
        }
        return ApiResponse.ok(uploadProperties.getUrlPrefix() + "/" + fileName);
    }

    private String pickExt(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件名不能为空");
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件缺少扩展名");
        }
        String ext = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!COVER_EXT_WHITELIST.contains(ext)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅支持 jpg/jpeg/png/webp/gif 格式");
        }
        return ext;
    }

    @Operation(summary = "发布球局", description = "发布人取自当前登录用户")
    @PostMapping
    @OperationLog("发布球局")
    public ApiResponse<GameResponse> create(@Valid @RequestBody GameCreateRequest req) {
        return ApiResponse.ok(gameService.create(UserContext.require(), req));
    }

    @Operation(summary = "标题查重", description = "公开接口，前端发布/编辑前预先校验。传 excludeId 编辑时排除自己")
    @GetMapping("title-check")
    public ApiResponse<java.util.Map<String, Object>> titleCheck(
            @RequestParam String title,
            @RequestParam(required = false) Long excludeId) {
        boolean available = gameService.titleAvailable(title, excludeId);
        return ApiResponse.ok(java.util.Map.of("available", available));
    }

    @Operation(summary = "球局列表")
    @GetMapping
    public ApiResponse<List<GameResponse>> list() {
        return ApiResponse.ok(gameService.list());
    }

    @Operation(summary = "管理端球局列表",
            description = "持 game:manage 权限；返回全部球局（含隐藏与未认证球局），PC 管理页专用")
    @GetMapping("manage")
    @OperationLog("查看管理端球局列表")
    public ApiResponse<List<GameResponse>> manageList() {
        permissionService.requirePermission(UserContext.require(), PermissionService.P_GAME_MANAGE);
        return ApiResponse.ok(gameService.listForManage());
    }

    @Operation(summary = "我参与过的球局", description = "当前登录用户报名过的所有球局，按 playDate 倒序")
    @GetMapping("my")
    public ApiResponse<List<GameResponse>> myGames() {
        return ApiResponse.ok(gameService.myGames(UserContext.require()));
    }

    @Operation(summary = "球局详情", description = "返回报名列表")
    @GetMapping("{id}")
    public ApiResponse<GameResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(gameService.detail(id));
    }

    /**
     * 编辑球局。仅允许发起人编辑；且仅限 status=0（报名中）状态。
     * 已编排 / 已结束 / 已取消的球局都不可改（会破坏编排、积分、轮排数据）。
     */
    @Operation(summary = "编辑球局",
            description = "仅发起人可编辑、仅限报名中（status=0）状态；复用发布时的字段校验")
    @PutMapping("{id}")
    @OperationLog("编辑球局")
    public ApiResponse<GameResponse> update(@PathVariable Long id,
                                            @Valid @RequestBody GameCreateRequest req) {
        return ApiResponse.ok(gameService.update(UserContext.require(), id, req));
    }

    /**
     * 删除球局。仅允许发起人删除；且仅限 status=0（报名中）状态。
     * 级联软删该球局下的报名记录；Redis 报名计数同步清零。
     */
    @Operation(summary = "删除球局",
            description = "仅发起人可删除、仅限报名中（status=0）状态；级联软删报名记录")
    @DeleteMapping("{id}")
    @OperationLog("删除球局")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        gameService.delete(UserContext.require(), id);
        return ApiResponse.ok(null);
    }

    @PutMapping("{id}/hide")
    @OperationLog("隐藏球局（发起人/管理员）")
    public ApiResponse<Void> hide(@PathVariable Long id) {
        gameService.hide(id, UserContext.require());
        return ApiResponse.ok(null);
    }

    @PutMapping("{id}/unhide")
    @OperationLog("恢复球局（发起人/管理员）")
    public ApiResponse<Void> unhide(@PathVariable Long id) {
        gameService.unhide(id, UserContext.require());
        return ApiResponse.ok(null);
    }

    /**
     * 报名（统一实名报名）
     */
    @Operation(summary = "报名", description = "写入报名记录并刷新报名计数")
    @PostMapping("{id}/register")
    @OperationLog("报名球局")
    public ApiResponse<GameResponse> register(@PathVariable Long id,
                                              @RequestBody(required = false) @Valid GameRegisterRequest req) {
        return ApiResponse.ok(gameService.register(UserContext.require(), id, req));
    }

    /** 取消报名：仅报名中的球局可取消 */
    @Operation(summary = "取消报名", description = "删除报名记录并刷新报名计数；已编排/已结束的球局不可取消")
    @DeleteMapping("{id}/register")
    @OperationLog("取消报名")
    public ApiResponse<GameResponse> cancelRegister(@PathVariable Long id) {
        return ApiResponse.ok(gameService.cancelRegister(UserContext.require(), id));
    }

    /**
     * 球局对阵（编排结果 + 现场比分）：公开接口，所有人可见，用来对着场地打球、录分。
     * 未登录访客只看到显示昵称。
     */
    @Operation(summary = "球局对阵", description = "编排后的对阵与比分；未登录访客只看得到显示昵称")
    @GetMapping("{id}/matches")
    public ApiResponse<GameMatchesResponse> matches(@PathVariable Long id) {
        return ApiResponse.ok(gameService.matches(id));
    }

    /** 自动编排：引擎按报名构成过滤 8 套方案生成对阵；可传 schemeId 强制指定（如 2 = 全混双） */
    @Operation(summary = "自动编排",
            description = "默认按报名人员性别构成过滤方案；传 schemeId 强制指定方案（1单打 2混双 3男双 4女双 5混搭混双优先 6混搭同性别 7混双纯随机 8全随机），传 roundRobin=true 生成循环赛")
    @PostMapping("{id}/arrange")
    @OperationLog("自动编排")
    public ApiResponse<ArrangeResponse> arrange(@PathVariable Long id,
                                                @RequestBody(required = false) @Valid ArrangeRequest req) {
        return ApiResponse.ok(gameService.arrange(UserContext.require(), id, req));
    }
}
