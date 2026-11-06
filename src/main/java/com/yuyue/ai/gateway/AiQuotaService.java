package com.yuyue.ai.gateway;

import com.yuyue.common.ErrorCode;
import com.yuyue.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 每用户每日调用配额（Redis 计数器）。
 * <p>
 * key 里带日期，天然按自然日隔离；TTL 给 1 天，过期自动清掉，不用定时任务。
 * <p>
 * 设计取舍：Redis 不可用时**放行**而不是拒绝。配额是「控制成本」的手段，
 * 不是安全边界；因为缓存抖动把所有 AI 功能一起关掉，代价比多花几毛钱 tokens 更大。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiQuotaService {

    private static final String KEY_PREFIX = "yuyue:ai:quota:";

    private final StringRedisTemplate redis;

    /**
     * 消耗一次配额，超限抛 {@link ErrorCode#AI_QUOTA_EXCEEDED}。
     *
     * @param userId     调用者；为 null（系统任务）时直接跳过
     * @param dailyLimit 每日上限，<= 0 表示不限制
     */
    public void consume(Long userId, int dailyLimit) {
        if (userId == null || dailyLimit <= 0) {
            return;
        }
        String key = KEY_PREFIX + LocalDate.now() + ":" + userId;
        try {
            Long used = redis.opsForValue().increment(key);
            if (used != null && used == 1L) {
                redis.expire(key, Duration.ofDays(1));
            }
            if (used != null && used > dailyLimit) {
                log.info("AI 每日配额已用完: userId={} used={} limit={}", userId, used, dailyLimit);
                throw new BizException(ErrorCode.AI_QUOTA_EXCEEDED,
                        "今日额度 " + dailyLimit + " 次已用完");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 配额检查失败，本次放行: userId={} error={}", userId, e.getMessage());
        }
    }
}
