package com.yuyue.web;

import com.yuyue.common.Constants;
import com.yuyue.exception.BizException;
import com.yuyue.common.ErrorCode;
import com.yuyue.util.JwtUtil;

import org.springframework.lang.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;

import java.util.regex.Pattern;

/**
 * 登录拦截器：校验 Authorization: Bearer <token>，并检查 Redis 黑名单
 * <p>
 * 公开只读接口（积分榜、球局列表 / 详情）无需登录，官网首页未登录也能展示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    /** GET /games/{id} */
    private static final Pattern GAME_DETAIL = Pattern.compile(".*/games/\\d+");

    /** GET /games/{id}/matches：编排结果与比分，未登录也要能看到 */
    private static final Pattern GAME_MATCHES = Pattern.compile(".*/games/\\d+/matches");

    /** GET /settings/home-poem：首页诗句，官网 & 小程序首页未登录也要能拉 */
    private static final Pattern HOME_POEM = Pattern.compile(".*/settings/home-poem");

    /** GET /tournaments/{id}：赛事详情 */
    private static final Pattern TOURNAMENT_DETAIL = Pattern.compile(".*/tournaments/\\d+");

    /** GET /tournaments/{id}/bracket：对阵图，未登录也要能看到赛程 */
    private static final Pattern TOURNAMENT_BRACKET = Pattern.compile(".*/tournaments/\\d+/bracket");

    /** GET /tournaments/{id}/matches：赛程录入页数据，同样公开 */
    private static final Pattern TOURNAMENT_MATCHES = Pattern.compile(".*/tournaments/\\d+/matches");

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(
        @NonNull HttpServletRequest request, 
        @Nullable HttpServletResponse response, 
        @Nullable Object handler) {
        // 公开接口也要尝试软鉴权（有 token 就解析用户身份，没 token 也不拦截）。
        // 这样 /games 列表才能按当前用户 is_verified 状态过滤认证球局。
        String auth = request.getHeader(HEADER);
        boolean hasAuth = auth != null && auth.startsWith(PREFIX);

        if (isPublicRead(request)) {
            if (hasAuth) {
                trySoftAuth(auth.substring(PREFIX.length()));
            }
            return true;
        }

        if (!hasAuth) {
            log.debug("未登录访问: {} {}", request.getMethod(), request.getRequestURI());
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String token = auth.substring(PREFIX.length());

        // 登出后的 token 直接拒绝
        Boolean blacklisted = redisTemplate.opsForSet().isMember(Constants.JWT_BLACKLIST, token);
        if (Boolean.TRUE.equals(blacklisted)) {
            log.debug("已登出 token 访问: {} {}", request.getMethod(), request.getRequestURI());
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        Long userId;
        try {
            userId = jwtUtil.parse(token);
        } catch (BizException e) {
            // token 过期 / 签名不对：JwtUtil 已统一抛 UNAUTHORIZED，这里只补一条可定位的日志
            log.debug("token 无效或已过期: {} {}", request.getMethod(), request.getRequestURI());
            throw e;
        }
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        UserContext.set(userId);
        return true;
    }

    /**
     * 软鉴权：公开接口有 token 时尝试解析身份。
     * 解析成功 → 写入 UserContext，让后端能识别当前用户状态；
     * 解析失败（过期/黑名单/无效）→ 静默忽略，当作未登录处理。
     */
    private void trySoftAuth(String token) {
        try {
            // 登出黑名单：软鉴权也不认
            Boolean blacklisted = redisTemplate.opsForSet().isMember(Constants.JWT_BLACKLIST, token);
            if (Boolean.TRUE.equals(blacklisted)) return;
            Long userId = jwtUtil.parse(token);
            if (userId != null) UserContext.set(userId);
        } catch (Exception ignored) {
            // 公开接口，token 无效就当作没登录，不报错
        }
    }

    /**
     * 公开只读：GET /ranking、GET /games、GET /games/{id}、赛事浏览相关
     * 这些接口返回的都是匿名数据（不含真实姓名），官网首页未登录时要能直接展示。
     * <p>
     * 赛事同理：列表 / 详情 / 对阵图 / 赛程都放出来，未登录也能逛；
     * 登录时靠软鉴权填充UserContext，接口才能标出「你报名的」「你的比赛」。
     * 注意 {@code /tournaments/partners/lookup} 结尾不匹配任何一条，仍需登录。
     */
    private boolean isPublicRead(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        return uri.contains("/ranking") || uri.endsWith("/games")
                || uri.endsWith("/games/title-check")
                || uri.endsWith("/courts")
                || uri.endsWith("/tournaments")
                || GAME_DETAIL.matcher(uri).matches()
                || GAME_MATCHES.matcher(uri).matches()
                || TOURNAMENT_DETAIL.matcher(uri).matches()
                || TOURNAMENT_BRACKET.matcher(uri).matches()
                || TOURNAMENT_MATCHES.matcher(uri).matches()
                || HOME_POEM.matcher(uri).matches();
    }

    @Override
    public void afterCompletion(
        @NonNull HttpServletRequest request, 
        @NonNull HttpServletResponse response, 
        @NonNull Object handler, 
        @Nullable Exception ex) {
        UserContext.clear();
    }
}
