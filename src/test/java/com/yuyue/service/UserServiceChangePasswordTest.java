package com.yuyue.service;

import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.config.EloProperties;
import com.yuyue.config.JwtProperties;
import com.yuyue.config.UploadProperties;
import com.yuyue.dto.AuthResponse;
import com.yuyue.dto.ChangePasswordRequest;
import com.yuyue.entity.User;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.UserMapper;
import com.yuyue.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 自助修改密码（changePassword）的核心约束：
 * 1) 原密码错误 / 新密码与原密码相同 → 拒绝，且不写库；
 * 2) 原密码正确 → 换新盐重新哈希、踢掉其他设备、返回新 token；
 * 3) 微信用户首次设置密码免原密码，但必须先绑定账号。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceChangePasswordTest {

    private static final String OLD_PASSWORD = "old123456";
    private static final String OLD_SALT = "0011223344556677";

    @Mock private UserMapper userMapper;
    @Mock private JwtUtil jwtUtil;
    @Mock private EloProperties eloProperties;
    @Mock private JwtProperties jwtProperties;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private WeChatService weChatService;
    @Mock private UploadProperties uploadProperties;
    @Mock private PermissionService permissionService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, jwtUtil, eloProperties, jwtProperties,
                redisTemplate, weChatService, uploadProperties, permissionService);
    }

    /** 与 UserService 内部实现同算法，用来构造「库里的旧密码」与断言新密码 */
    private String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private User accountUser(long id) {
        User u = new User();
        u.setId(id);
        u.setAccount("stu_" + id);
        u.setName("球友" + id);
        u.setPasswordHash(hash(OLD_PASSWORD, OLD_SALT) + ":" + OLD_SALT);
        return u;
    }

    private ChangePasswordRequest req(String oldPassword, String newPassword) {
        ChangePasswordRequest r = new ChangePasswordRequest();
        r.setOldPassword(oldPassword);
        r.setNewPassword(newPassword);
        return r;
    }

    @Test
    @DisplayName("原密码错误：抛 PASSWORD_MISMATCH，不写库、不踢会话")
    void rejectsWrongOldPassword() {
        when(userMapper.selectById(7L)).thenReturn(accountUser(7L));

        BizException e = assertThrows(BizException.class,
                () -> userService.changePassword(7L, req("wrong-pass", "new123456")));

        assertEquals(ErrorCode.PASSWORD_MISMATCH.getCode(), e.getCode());
        verify(userMapper, never()).updateById(any(User.class));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("原密码正确：换新盐重新哈希、踢掉其他设备、返回新 token")
    void changesPasswordAndKicksOtherDevices() {
        User user = accountUser(7L);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(jwtProperties.getExpireHours()).thenReturn(72);
        when(jwtUtil.issue(7L)).thenReturn("new-token");

        AuthResponse resp = userService.changePassword(7L, req(OLD_PASSWORD, "new123456"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        String saved = captor.getValue().getPasswordHash();
        assertTrue(Pattern.compile("^[0-9a-f]{64}:[0-9a-f]{16}$").matcher(saved).matches(),
                "密码哈希应为 hash(64位hex):salt(16位hex)，实际=" + saved);

        String[] parts = saved.split(":", 2);
        assertEquals(hash("new123456", parts[1]), parts[0], "新哈希应能校验通过新密码");
        assertNotEquals(hash(OLD_PASSWORD, parts[1]), parts[0], "新哈希不应还能校验旧密码");
        assertNotEquals(OLD_SALT, parts[1], "应换新盐");

        verify(valueOps).set(
                eq(Constants.JWT_PWD_RESET_PREFIX + 7L),
                anyString(),
                eq(Duration.ofHours(72L)));

        assertEquals("new-token", resp.getToken());
        assertTrue(resp.getHasPassword(), "改密后 hasPassword 应为 true");
    }

    @Test
    @DisplayName("新密码与原密码相同：拒绝，避免「改了等于没改」")
    void rejectsSameAsOldPassword() {
        when(userMapper.selectById(7L)).thenReturn(accountUser(7L));

        BizException e = assertThrows(BizException.class,
                () -> userService.changePassword(7L, req(OLD_PASSWORD, OLD_PASSWORD)));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("微信用户已绑账号、从未设密码：免原密码首次设置成功")
    void wechatUserSetsPasswordWithoutOldPassword() {
        User wxUser = new User();
        wxUser.setId(9L);
        wxUser.setAccount("wx_9");
        wxUser.setWxOpenid("openid_9");
        when(userMapper.selectById(9L)).thenReturn(wxUser);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(jwtProperties.getExpireHours()).thenReturn(72);
        when(jwtUtil.issue(9L)).thenReturn("token-9");

        AuthResponse resp = userService.changePassword(9L, req(null, "first123"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        String saved = captor.getValue().getPasswordHash();
        String[] parts = saved.split(":", 2);
        assertEquals(hash("first123", parts[1]), parts[0]);
        assertTrue(resp.getHasPassword());
    }

    @Test
    @DisplayName("微信用户没绑账号：拒绝设置密码（密码没有登录入口）")
    void rejectsWechatUserWithoutAccount() {
        User wxUser = new User();
        wxUser.setId(10L);
        wxUser.setWxOpenid("openid_10");
        when(userMapper.selectById(10L)).thenReturn(wxUser);

        BizException e = assertThrows(BizException.class,
                () -> userService.changePassword(10L, req(null, "first123")));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        verify(userMapper, never()).updateById(any(User.class));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("已设密码但未填原密码：拒绝")
    void rejectsMissingOldPassword() {
        when(userMapper.selectById(7L)).thenReturn(accountUser(7L));

        BizException e = assertThrows(BizException.class,
                () -> userService.changePassword(7L, req("", "new123456")));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }
}
