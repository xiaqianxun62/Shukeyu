package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.ErrorCode;
import com.yuyue.config.EloProperties;
import com.yuyue.config.UploadProperties;
import com.yuyue.dto.AuthResponse;
import com.yuyue.dto.LoginRequest;
import com.yuyue.dto.ProfileUpdateRequest;
import com.yuyue.dto.RegisterRequest;
import com.yuyue.dto.WxLoginRequest;
import com.yuyue.entity.User;
import com.yuyue.exception.BizException;
import com.yuyue.common.Constants;
import com.yuyue.config.JwtProperties;
import com.yuyue.mapper.UserMapper;
import com.yuyue.util.JwtUtil;
import com.yuyue.util.ImageUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String SALT_CHARS = "0123456789abcdef";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Authorization 头前缀，与 AuthInterceptor 保持一致 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final EloProperties eloProperties;
    private final JwtProperties jwtProperties;
    private final StringRedisTemplate redisTemplate;
    private final WeChatService weChatService;
    private final UploadProperties uploadProperties;

    public AuthResponse register(RegisterRequest req) {
        Long existing = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getAccount, req.getAccount()));
        if (existing > 0) {
            throw new BizException(ErrorCode.USER_EXISTS);
        }
        checkNameUnique(null, req.getName());

        String salt = randomSalt();
        User user = new User();
        user.setAccount(req.getAccount());
        user.setName(req.getName());
        // 昵称选填：留空则由展示层回退到真实姓名
        String nickname = req.getNickname() == null ? null : req.getNickname().trim();
        user.setNickname(nickname == null || nickname.isEmpty() ? null : nickname);
        user.setGender(req.getGender());
        user.setPasswordHash(hash(req.getPassword(), salt) + ":" + salt);
        user.setRating(eloProperties.getDefaultRating());
        user.setGamesPlayed(0);
        user.setWinCount(0);
        user.setLossCount(0);
        userMapper.insert(user);

        return buildAuthResponse(user, jwtUtil.issue(user.getId()));
    }

    public AuthResponse login(LoginRequest req) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getAccount, req.getAccount()));
        if (user == null) {
            throw new BizException(ErrorCode.PASSWORD_ERROR);
        }
        // 微信用户没有密码，直接拒绝账号密码登录，提示走微信登录或先设置密码
        if (user.getPasswordHash() == null || !user.getPasswordHash().contains(":")) {
            throw new BizException(ErrorCode.PASSWORD_ERROR);
        }
        String[] parts = user.getPasswordHash().split(":", 2);
        if (parts.length != 2 || !hash(req.getPassword(), parts[1]).equals(parts[0])) {
            throw new BizException(ErrorCode.PASSWORD_ERROR);
        }
        return buildAuthResponse(user, jwtUtil.issue(user.getId()));
    }

    /**
     * 微信小程序登录：code → openid，已存在则直接登录，否则自动注册并返回 JWT。
     * 首次注册的用户没有姓名账号，需后续调用 profile 接口完善。
     */
    public AuthResponse wxLogin(WxLoginRequest req) {
        String openid = weChatService.code2Openid(req.getCode());
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getWxOpenid, openid));
        boolean isNew = user == null;
        if (isNew) {
            user = new User();
            user.setWxOpenid(openid);
            user.setName(defaultName(req.getNickName(), openid));
            user.setAvatar(req.getAvatarUrl());
            user.setGender(0);
            user.setRating(eloProperties.getDefaultRating());
            user.setGamesPlayed(0);
            user.setWinCount(0);
            user.setLossCount(0);
            userMapper.insert(user);
            log.info("微信用户首次登录: openid={}, userId={}", openid, user.getId());
        } else if (req.getAvatarUrl() != null && !req.getAvatarUrl().isBlank()) {
            // 已存在用户也同步更新头像
            user.setAvatar(req.getAvatarUrl());
            userMapper.updateById(user);
        }
        AuthResponse resp = buildAuthResponse(user, jwtUtil.issue(user.getId()));
        resp.setNewUser(isNew);
        return resp;
    }

    /**
     * 完善资料：微信用户补充姓名 / 性别 / 账号（账号非空时做唯一校验）
     */
    public AuthResponse updateProfile(Long userId, ProfileUpdateRequest req) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            String newName = req.getName().trim();
            checkNameUnique(userId, newName);
            user.setName(newName);
        }
        // 昵称：不传(null)=不修改；空串=清除；非空=覆盖
        if (req.getNickname() != null) {
            String trimmed = req.getNickname().trim();
            user.setNickname(trimmed.isEmpty() ? null : trimmed);
        }
        // 积分榜展示名偏好：0 真实姓名 1 昵称，null 表示不修改
        if (req.getNameDisplay() != null) {
            user.setNameDisplay(req.getNameDisplay());
        }
        if (req.getGender() != null) {
            user.setGender(req.getGender());
        }
        if (req.getAccount() != null && !req.getAccount().isBlank()) {
            String account = req.getAccount().trim();
            Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getAccount, account)
                    .ne(User::getId, userId));
            if (exists > 0) {
                throw new BizException(ErrorCode.USER_EXISTS);
            }
            user.setAccount(account);
        }
        if (req.getAvatar() != null && !req.getAvatar().isBlank()) {
            String avatar = req.getAvatar().trim();
            if (!avatar.startsWith("http://") && !avatar.startsWith("https://") && !avatar.startsWith("/")) {
                throw new BizException(ErrorCode.PARAM_ERROR, "头像地址必须以 http(s):// 开头或以 / 开头的相对路径");
            }
            user.setAvatar(avatar);
        }
        userMapper.updateById(user);
        return buildAuthResponse(user, null);
    }

    private String defaultName(String nickName, String openid) {
        if (nickName != null && !nickName.isBlank()) {
            return nickName.trim();
        }
        return "微信球友#" + Math.abs(openid.hashCode() % 9000 + 1000);
    }

    /** 当前登录用户信息 */
    public AuthResponse me(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return buildAuthResponse(user, null);
    }

    /** 上传扩展名白名单 */
    private static final Set<String> AVATAR_EXT_WHITELIST =
            Set.of("jpg", "jpeg", "png", "webp", "gif");

    /**
     * 上传个人头像：校验类型与大小，落地到本地 uploads 目录，更新 user.avatar 并返回可访问 URL
     */
    public String uploadAvatar(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "头像文件不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅支持图片格式");
        }
        long size = file.getSize();
        if (size > uploadProperties.getMaxSize()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件大小超过限制");
        }
        String ext = pickAvatarExt(file.getOriginalFilename());
        String fileName = "avatar_" + userId + "_" + System.currentTimeMillis()
                + "_" + RANDOM.nextInt(10000) + "." + ext;
        Path dir = Paths.get(uploadProperties.getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName).normalize();
            if (!target.startsWith(dir)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "非法文件名");
            }
            file.transferTo(target.toFile());

            // 生成缩略图（200×200 JPEG），失败不阻断主流程
            generateThumbSilently(target, dir.resolve(ImageUtils.thumbFileName(fileName)),
                    ImageUtils.AVATAR_THUMB_SIZE, ImageUtils.AVATAR_THUMB_SIZE,
                    contentType, file.getOriginalFilename());
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "头像保存失败");
        }
        String url = uploadProperties.getUrlPrefix() + "/" + fileName;
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        user.setAvatar(url);
        userMapper.updateById(user);
        return url;
    }

    /**
     * 静默生成缩略图：失败只打 warn，不抛异常、不阻断主流程。
     * 历史图片（上传时还没缩略图功能的）不存在 _thumb 文件，前端 SmartImage 会自动回退到原图。
     */
    private void generateThumbSilently(Path src, Path thumb, int maxW, int maxH,
                                       String contentType, String originalName) {
        try {
            ImageUtils.generateThumbnail(src, thumb, maxW, maxH, contentType, originalName);
        } catch (Exception e) {
            log.warn("头像缩略图生成失败（不影响主流程）: {} -> {}", src.getFileName(), thumb.getFileName(), e);
        }
    }

    /** 取扩展名（小写），不在白名单内时抛错 */
    private String pickAvatarExt(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件名不能为空");
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件缺少扩展名");
        }
        String ext = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!AVATAR_EXT_WHITELIST.contains(ext)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅支持 jpg/jpeg/png/webp/gif 格式");
        }
        return ext;
    }

    /**
     * 登出：把 token 加入 Redis 黑名单，之后 AuthInterceptor 会直接拒绝该 token。
     * 黑名单是 Set，TTL 设在 key 上，取 token 的最大存活时间，到期自动清理。
     */
    public void logout(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return;
        }
        redisTemplate.opsForSet().add(Constants.JWT_BLACKLIST, token);
        redisTemplate.expire(Constants.JWT_BLACKLIST, Duration.ofHours(jwtProperties.getExpireHours()));
    }

    /**
     * 用户昵称唯一校验（业务层主动报错）。
     *
     * @param excludeId 排除的用户 id（编辑资料传自己，注册传 null）
     */
    private void checkNameUnique(Long excludeId, String name) {
        if (name == null || name.isBlank()) return;
        Long cnt = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getName, name.trim())
                .ne(excludeId != null, User::getId, excludeId));
        if (cnt != null && cnt > 0) {
            throw new BizException(ErrorCode.USER_NAME_DUPLICATE);
        }
    }

    /** 前端公开查重接口：未登录也能调（注册前预先校验） */
    public boolean nameAvailable(String name, Long excludeId) {
        if (name == null || name.isBlank()) return true;
        Long cnt = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getName, name.trim())
                .ne(excludeId != null, User::getId, excludeId));
        return cnt == null || cnt == 0;
    }

    private AuthResponse buildAuthResponse(User user, String token) {
        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .name(user.getName())
                .nickname(user.getNickname())
                .nameDisplay(user.getNameDisplay() == null ? 1 : user.getNameDisplay())
                .gender(user.getGender())
                .account(user.getAccount())
                .avatar(user.getAvatar())
                .rating(user.getRating())
                .gamesPlayed(user.getGamesPlayed())
                .isAdmin(user.getIsAdmin() != null && user.getIsAdmin() == 1)
                .isVerified(user.getIsVerified() != null && user.getIsVerified() == 1)
                .build();
    }

    private String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String randomSalt() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(SALT_CHARS.charAt(RANDOM.nextInt(SALT_CHARS.length())));
        }
        return sb.toString();
    }
}
