package com.yuyue.service;

import com.yuyue.common.ErrorCode;
import com.yuyue.common.Constants;
import com.yuyue.config.EloProperties;
import com.yuyue.config.JwtProperties;
import com.yuyue.config.UploadProperties;
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

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 管理员重置密码（adminResetPassword）的三条核心约束：
 * 1) 正常账号重置为 123456 的哈希并踢旧会话下线；
 * 2) 非存在的用户拒绝；
 * 3) 未设置账号的微信用户拒绝（无法账号密码登录，重置无意义）。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceAdminResetPasswordTest {

    @Mock private UserMapper userMapper;
    @Mock private JwtUtil jwtUtil;
    @Mock private EloProperties eloProperties;
    @Mock private JwtProperties jwtProperties;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private WeChatService weChatService;
    @Mock private UploadProperties uploadProperties;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, jwtUtil, eloProperties, jwtProperties,
                redisTemplate, weChatService, uploadProperties);
    }

    private User accountUser(long id) {
        User u = new User();
        u.setId(id);
        u.setAccount("stu_" + id);
        u.setPasswordHash("oldhash:salt");
        return u;
    }

    @Test
    @DisplayName("正常重置：写入 newSalt:hash 格式的密码哈希，并在 Redis 记踢下线标记")
    void resetsPasswordAndKicksOldSessions() {
        User target = accountUser(7L);
        when(userMapper.selectById(7L)).thenReturn(target);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(jwtProperties.getExpireHours()).thenReturn(72);

        userService.adminResetPassword(7L);

        // 落库的是 hash(123456):salt 的形态
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User saved = captor.getValue();
        assertNotNull(saved.getPasswordHash());
        assertTrue(Pattern.compile("^[0-9a-f]{64}:[0-9a-f]{16}$")
                .matcher(saved.getPasswordHash()).matches(),
                "密码哈希应为 hash(64位hex):salt(16位hex) 格式，实际=" + saved.getPasswordHash());
        assertNotEquals("oldhash", saved.getPasswordHash());

        // 踢下线标记：key = 前缀+userId，value=当前毫秒，TTL=token 存活时长
        verify(valueOps).set(
                eq(Constants.JWT_PWD_RESET_PREFIX + 7L),
                anyString(),
                eq(Duration.ofHours(72L)));
    }

    @Test
    @DisplayName("用户不存在：抛 USER_NOT_FOUND，且不写库、不记踢下线标记")
    void rejectsUnknownUser() {
        when(userMapper.selectById(999L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> userService.adminResetPassword(999L));
        assertEquals(ErrorCode.USER_NOT_FOUND.getCode(), e.getCode());
        verify(userMapper, never()).updateById(any(User.class));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("微信用户未设置账号：拒绝重置（无法账号密码登录）")
    void rejectsUserWithoutAccount() {
        User wxUser = new User();
        wxUser.setId(3L);
        wxUser.setAccount(null);
        when(userMapper.selectById(3L)).thenReturn(wxUser);

        BizException e = assertThrows(BizException.class,
                () -> userService.adminResetPassword(3L));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        verify(userMapper, never()).updateById(any(User.class));
        verify(redisTemplate, never()).opsForValue();
    }
}
