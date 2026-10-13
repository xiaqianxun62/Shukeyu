package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.entity.User;
import com.yuyue.entity.VisitLog;
import com.yuyue.mapper.UserMapper;
import com.yuyue.mapper.VisitLogMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 浏览访问日志：异步落库 + 管理员查询。
 *
 * <p>写入走独立单线程池：访问日志是"尽力而为"的旁路记录，
 * 绝不能因为数据库抖动拖慢业务请求（拦截器里只做入队）。
 * 队列满时丢弃新日志并打警告，保护内存不被恶意刷接口撑爆。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisitLogService {

    private final VisitLogMapper visitLogMapper;
    private final UserMapper userMapper;

    /** 队列容量：约可缓冲 10 秒内的高频访问 */
    private static final int QUEUE_CAPACITY = 4096;

    private final ExecutorService writer = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "visit-log-writer");
                t.setDaemon(true);
                return t;
            },
            // 队列满：丢弃本次记录并打点，绝不阻塞业务请求
            (r, e) -> log.warn("访问日志队列已满，丢弃本次记录"));

    /** 异步记录一次访问（拦截器调用，非阻塞） */
    public void recordAsync(VisitLog visitLog) {
        try {
            writer.execute(() -> {
                try {
                    visitLog.setCreateTime(LocalDateTime.now());
                    visitLogMapper.insert(visitLog);
                } catch (Exception e) {
                    log.warn("访问日志写入失败: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("访问日志入队失败: {}", e.getMessage());
        }
    }

    /**
     * 查询访问日志（管理员）：按时间倒序，支持按 IP 过滤。
     * 顺带把 userId 关联的昵称查出来，前端免二次请求。
     */
    public List<VisitLogView> list(String ip, int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        LambdaQueryWrapper<VisitLog> qw = new LambdaQueryWrapper<VisitLog>()
                .orderByDesc(VisitLog::getCreateTime)
                .orderByDesc(VisitLog::getId)
                .last("LIMIT " + size);
        if (ip != null && !ip.isBlank()) {
            qw.eq(VisitLog::getIp, ip.trim());
        }
        List<VisitLog> logs = visitLogMapper.selectList(qw);
        if (logs.isEmpty()) {
            return List.of();
        }

        // 批量取用户昵称，避免循环单查
        List<Long> userIds = logs.stream()
                .map(VisitLog::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> names = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .filter(u -> u != null && u.getId() != null)
                        .collect(Collectors.toMap(User::getId, u -> u.getName() == null ? "" : u.getName(), (a, b) -> a));

        return logs.stream()
                .map(v -> {
                    VisitLogView view = new VisitLogView();
                    view.setId(v.getId());
                    view.setIp(v.getIp());
                    view.setUserId(v.getUserId());
                    view.setUserName(v.getUserId() == null ? null : names.get(v.getUserId()));
                    view.setPath(v.getPath());
                    view.setMethod(v.getMethod());
                    view.setUserAgent(v.getUserAgent());
                    view.setReferer(v.getReferer());
                    view.setCreateTime(v.getCreateTime());
                    return view;
                })
                .toList();
    }

    @PreDestroy
    public void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 访问日志视图：附带登录用户昵称 */
    @lombok.Data
    public static class VisitLogView {
        private Long id;
        private String ip;
        private Long userId;
        private String userName;
        private String path;
        private String method;
        private String userAgent;
        private String referer;
        private LocalDateTime createTime;
    }
}
