package com.yuyue.ai.gateway;

import com.yuyue.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轻量熔断器（单机内存版）。
 * <p>
 * 不引 Resilience4j 的理由：这里只需要「连续失败 N 次 → 冷却 M 秒 → 放一个请求试探」，
 * 三十行代码能覆盖，且没有额外依赖与配置面。多实例部署时各自熔断，够用。
 * <p>
 * 状态机：闭合（正常）→ 连续失败达阈值 → 打开（直接拒绝）→ 冷却结束 → 半开（放一个试探）
 * → 成功则闭合、失败则重新打开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCircuitBreaker {

    private final AiProperties properties;

    /** 连续失败次数（成功即清零） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 熔断到期时刻（epoch ms），0 表示未熔断 */
    private volatile long openUntilMs = 0L;

    /** 是否处于「冷却结束、放一个请求试探」的半开状态 */
    private volatile boolean halfOpen = false;

    /** 本次请求是否放行 */
    public boolean allowRequest() {
        long openUntil = openUntilMs;
        if (openUntil == 0L) {
            return true;
        }
        if (System.currentTimeMillis() < openUntil) {
            return false;
        }
        synchronized (this) {
            if (openUntilMs == openUntil) {
                openUntilMs = 0L;
                halfOpen = true;
                log.info("AI 熔断冷却结束，进入半开状态，放行试探请求");
            }
        }
        return true;
    }

    public void onSuccess() {
        consecutiveFailures.set(0);
        halfOpen = false;
        openUntilMs = 0L;
    }

    public void onFailure() {
        // 半开状态下试探失败：说明上游还没恢复，直接重新熔断
        if (halfOpen) {
            open();
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= Math.max(properties.getFailureThreshold(), 1)) {
            open();
        }
    }

    private void open() {
        int seconds = Math.max(properties.getOpenSeconds(), 1);
        consecutiveFailures.set(0);
        halfOpen = false;
        openUntilMs = System.currentTimeMillis() + seconds * 1000L;
        log.warn("AI 连续失败达到阈值，熔断 {} 秒", seconds);
    }
}
