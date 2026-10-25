package com.yuyue.util;

import com.yuyue.config.JwtProperties;
import com.yuyue.exception.BizException;
import com.yuyue.common.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：HMAC-SHA256 签发与解析
 */
@Component
public class JwtUtil {

    private final JwtProperties props;
    private final SecretKey key;

    public JwtUtil(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(Long userId) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + props.getExpireHours() * 3600_000L);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expire)
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 token，返回完整 claims；非法/过期抛 UNAUTHORIZED。
     * 调用方可读到 issuedAt，用于与「按用户踢下线标记」对比 token 新旧。
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 解析并校验 token，返回 userId；非法/过期抛 UNAUTHORIZED
     */
    public Long parse(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }
}
