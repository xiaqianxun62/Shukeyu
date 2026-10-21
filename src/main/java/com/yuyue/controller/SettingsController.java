package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.common.ErrorCode;
import com.yuyue.common.OperationLog;
import com.yuyue.config.UploadProperties;
import com.yuyue.entity.User;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.UserMapper;
import com.yuyue.service.SettingsService;
import com.yuyue.web.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 站点可配置文案。Redis 存，零数据库依赖。
 * GET 公开（小程序首页未登录也能拉），PUT 仅管理员。
 */
@RestController
@RequestMapping("settings")
@RequiredArgsConstructor
@Tag(name = "站点文案", description = "可在 PC 官网设置的站点展示文案")
public class SettingsController {

    /** 公告媒体上传白名单：图片 + 视频 */
    private static final Set<String> MEDIA_IMAGE_EXT = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> MEDIA_VIDEO_EXT = Set.of("mp4", "webm", "ogg", "mov", "avi");
    private static final Random RANDOM = new Random();

    private final SettingsService settingsService;
    private final UserMapper userMapper;
    private final UploadProperties uploadProperties;

    /** 首页诗句签名：公开接口，小程序首页未登录也能拉 */
    @Operation(summary = "首页诗句文案", description = "公开接口；未设置时返回默认诗句")
    @GetMapping("home-poem")
    public ApiResponse<Map<String, String>> getHomePoem() {
        return ApiResponse.ok(settingsService.getHomePoem());
    }

    /** 仅管理员可修改。前端只传 line1 / line2 / source 中需要改的字段 */
    @Operation(summary = "修改首页诗句文案", description = "仅管理员可改；未传的字段保持原值")
    @PutMapping("home-poem")
    @OperationLog("修改首页诗句文案")
    public ApiResponse<Map<String, String>> updateHomePoem(
            @RequestBody(required = false) Map<String, String> patch) {
        User me = userMapper.selectById(UserContext.require());
        if (me == null || me.getIsAdmin() == null || me.getIsAdmin() != 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可修改站点文案");
        }
        return ApiResponse.ok(settingsService.updateHomePoem(patch));
    }

    /** 首页弹窗公告：公开接口，小程序 & 官网首页未登录也能拉 */
    @Operation(summary = "首页弹窗公告", description = "公开接口；enabled=0 或 content 为空时前端不展示")
    @GetMapping("home-notice")
    public ApiResponse<Map<String, String>> getHomeNotice() {
        return ApiResponse.ok(settingsService.getHomeNotice());
    }

    /** 仅管理员可修改。内容更新后 version 自增，用户端会重新弹一次 */
    @Operation(summary = "修改首页弹窗公告", description = "仅管理员可改；未传的字段保持原值")
    @PutMapping("home-notice")
    @OperationLog("修改首页弹窗公告")
    public ApiResponse<Map<String, String>> updateHomeNotice(
            @RequestBody(required = false) Map<String, String> patch) {
        User me = userMapper.selectById(UserContext.require());
        if (me == null || me.getIsAdmin() == null || me.getIsAdmin() != 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可修改站点文案");
        }
        return ApiResponse.ok(settingsService.updateHomeNotice(patch));
    }

    /**
     * 上传公告媒体（图片 / 视频）：仅管理员，落盘到 uploads 目录，返回可公开访问的 /uploads/xxx。
     * 媒体类型按扩展名判定（jpg/png/webp/gif = image，mp4/webm/ogg/mov/avi = video）。
     */
    @Operation(summary = "上传公告媒体", description = "multipart/form-data，文件字段名 file；支持图片和视频，返回 /uploads/xxx URL")
    @PostMapping("upload-notice-media")
    @OperationLog("上传公告媒体")
    public ApiResponse<Map<String, String>> uploadNoticeMedia(@RequestParam("file") MultipartFile file) {
        requireAdmin();
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail(ErrorCode.PARAM_ERROR, "媒体文件不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !(contentType.startsWith("image/") || contentType.startsWith("video/"))) {
            return ApiResponse.fail(ErrorCode.PARAM_ERROR, "仅支持图片或视频格式");
        }
        if (file.getSize() > uploadProperties.getMaxSize()) {
            return ApiResponse.fail(ErrorCode.PARAM_ERROR, "文件大小超过限制（"
                    + (uploadProperties.getMaxSize() / 1024 / 1024) + "MB）");
        }
        String ext = pickMediaExt(file.getOriginalFilename());
        String kind = MEDIA_IMAGE_EXT.contains(ext) ? "image" : "video";
        String fileName = "notice_" + System.currentTimeMillis() + "_" + RANDOM.nextInt(10000) + "." + ext;
        Path dir = Paths.get(uploadProperties.getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName).normalize();
            if (!target.startsWith(dir)) {
                return ApiResponse.fail(ErrorCode.PARAM_ERROR, "非法文件名");
            }
            file.transferTo(target.toFile());
        } catch (IOException e) {
            return ApiResponse.fail(ErrorCode.SYSTEM_ERROR, "媒体文件保存失败");
        }
        Map<String, String> out = Map.of(
                "mediaType", kind,
                "mediaUrl", uploadProperties.getUrlPrefix() + "/" + fileName);
        return ApiResponse.ok(out);
    }

    /** 校验并返回媒体扩展名（小写） */
    private String pickMediaExt(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件名不能为空");
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件缺少扩展名");
        }
        String ext = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!MEDIA_IMAGE_EXT.contains(ext) && !MEDIA_VIDEO_EXT.contains(ext)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "仅支持 jpg/png/webp/gif 图片或 mp4/webm/ogg/mov/avi 视频");
        }
        return ext;
    }

    private void requireAdmin() {
        User me = userMapper.selectById(UserContext.require());
        if (me == null || me.getIsAdmin() == null || me.getIsAdmin() != 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可操作");
        }
    }
}
