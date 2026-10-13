package com.yuyue.web;

import com.yuyue.entity.VisitLog;
import com.yuyue.service.VisitLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 浏览访问日志拦截器：记录访问者 IP 地址、请求路径、UA、来源页与登录用户。
 *
 * <p>只记录 GET（浏览行为），静态资源 / 文档 / 错误页与 {@link AccessLogInterceptor}
 * 保持同一套排除规则，避免噪音。落库走 {@link VisitLogService} 的异步队列，
 * preHandle 不做任何 IO。
 *
 * <p>注册顺序 order(2)：afterCompletion 按注册逆序回调（2 → 1 → 0），
 * 本拦截器最先执行，此时 UserContext 尚未被 {@link AuthInterceptor} 清理，可读到 userId。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitLogInterceptor implements HandlerInterceptor {

    private static final String HEADER_XFF = "X-Forwarded-For";
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    private final VisitLogService visitLogService;

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler) {
        try {
            // 预检 / 非浏览请求不记
            if (!"GET".equalsIgnoreCase(request.getMethod())) {
                return true;
            }
            VisitLog visitLog = new VisitLog();
            visitLog.setIp(trim(resolveIp(request), 64));
            visitLog.setUserId(UserContext.get());
            visitLog.setMethod(request.getMethod());
            visitLog.setPath(trim(request.getRequestURI()
                    + (isBlank(request.getQueryString()) ? "" : "?" + request.getQueryString()), 255));
            visitLog.setUserAgent(trim(request.getHeader("User-Agent"), 500));
            visitLog.setReferer(trim(request.getHeader("Referer"), 500));
            visitLogService.recordAsync(visitLog);
        } catch (Exception e) {
            // 记录失败绝不影响业务请求
            log.warn("访问日志采集失败: {}", e.getMessage());
        }
        return true;
    }

    /**
     * 取真实客户端 IP：
     * 反向代理（Nginx）场景下优先 X-Forwarded-For 首个非 unknown 段，
     * 其次 X-Real-IP，最后回退容器看到的 remoteAddr。
     */
    private String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader(HEADER_XFF);
        if (!isBlank(xff)) {
            for (String part : xff.split(",")) {
                String ip = part.trim();
                if (!ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                    return ip;
                }
            }
        }
        String realIp = request.getHeader(HEADER_X_REAL_IP);
        if (!isBlank(realIp) && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 超长字段截断，避免超出列宽导致整条插入失败 */
    private String trim(String s, int maxLen) {
        if (s == null) return "";
        String v = s.trim();
        return v.length() > maxLen ? v.substring(0, maxLen) : v;
    }
}
