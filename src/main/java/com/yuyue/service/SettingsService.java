package com.yuyue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站点可配置文案。用 Redis 存 key → value map，避免为每个文案字段建表。
 * 首次读取时若 Redis 为空返回默认值（前端可用这些默认值初始化页面）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final String REDIS_KEY_HOME_POEM = "yuyue:settings:home_poem";

    /** 首页弹窗公告：enabled=1 时两端首页展示；version 在内容更新时自增，前端据此重新弹 */
    private static final String REDIS_KEY_HOME_NOTICE = "yuyue:settings:home_notice";

    /**
     * 默认首页弹窗（默认关闭，首次读取返回）。
     * frequency：always=每次进入都弹 / daily=每天一次(默认) / once=同一内容只弹一次；
     * width/height 单位 px，height=0 表示高度自适应；颜色为 hex 色值。
     */
    private static final Map<String, String> DEFAULT_HOME_NOTICE = Map.ofEntries(
            Map.entry("enabled", "0"),
            Map.entry("title", "公告"),
            Map.entry("content", ""),
            Map.entry("version", "1"),
            Map.entry("frequency", "daily"),
            Map.entry("bgColor", "#fffdf8"),
            Map.entry("titleColor", "#22312a"),
            Map.entry("contentColor", "#5a726d"),
            Map.entry("btnBgColor", "#14665b"),
            Map.entry("btnTextColor", "#ffffff"),
            Map.entry("width", "420"),
            Map.entry("height", "0"),
            Map.entry("mediaType", ""),   // ""=无媒体 / image / video
            Map.entry("mediaUrl", "")     // 媒体地址（/uploads/xxx）
    );

    /** 默认诗句（首次读取时返回，同时落库当种子） */
    private static final Map<String, String> DEFAULT_HOME_POEM = Map.of(
            "line1", "无言独上西楼，月如钩，",
            "line2", "寂寞梧桐深院锁清秋。",
            "source", "—— 五代 · 李煜《相见欢》"
    );

    private final StringRedisTemplate redis;

    /** GET /settings/home-poem（公开）：返回当前配置，空则返回默认值 */
    public Map<String, String> getHomePoem() {
        Map<Object, Object> stored = redis.opsForHash().entries(REDIS_KEY_HOME_POEM);
        if (stored == null || stored.isEmpty()) {
            return new LinkedHashMap<>(DEFAULT_HOME_POEM);
        }
        Map<String, String> out = new LinkedHashMap<>();
        DEFAULT_HOME_POEM.keySet().forEach(k -> {
            Object v = stored.get(k);
            out.put(k, v == null ? DEFAULT_HOME_POEM.get(k) : v.toString());
        });
        return out;
    }

    /**
     * PUT /settings/home-poem（登录即可改）：接收 line1 / line2 / source 三个可选字段。
     * 前端只传需要改的字段，后端做 merge（null/空字符串视为不修改）。
     */
    public Map<String, String> updateHomePoem(Map<String, String> patch) {
        // 先拿现有的（或默认的）当 base
        Map<String, String> base = new HashMap<>(getHomePoem());
        // 只覆盖前端传了的、且非空的字段
        if (patch != null) {
            for (String key : DEFAULT_HOME_POEM.keySet()) {
                String v = patch.get(key);
                if (v != null && !v.isBlank()) {
                    base.put(key, v.trim());
                }
            }
        }
        redis.opsForHash().putAll(REDIS_KEY_HOME_POEM, base);
        // 过期 90 天：防止 Redis 重启后永久存在垃圾配置
        redis.expire(REDIS_KEY_HOME_POEM, java.time.Duration.ofDays(90));
        log.info("站点文案 home-poem 更新: {}", base);
        return base;
    }

    /** GET /settings/home-notice（公开）：返回当前弹窗配置，空则返回默认值（默认关闭） */
    public Map<String, String> getHomeNotice() {
        Map<Object, Object> stored = redis.opsForHash().entries(REDIS_KEY_HOME_NOTICE);
        if (stored == null || stored.isEmpty()) {
            return new LinkedHashMap<>(DEFAULT_HOME_NOTICE);
        }
        Map<String, String> out = new LinkedHashMap<>();
        DEFAULT_HOME_NOTICE.keySet().forEach(k -> {
            Object v = stored.get(k);
            out.put(k, v == null ? DEFAULT_HOME_NOTICE.get(k) : v.toString());
        });
        return out;
    }

    /**
     * PUT /settings/home-notice（仅管理员）：接收 enabled / title / content / 颜色 / 尺寸 / 媒体等字段。
     * 只要任意字段有变化就把 version 自增 —— 前端存的「已关闭」标记是按 version 记的，
     * 所以管理员更新公告后，用户端会重新弹一次。
     * mediaType 特殊：传空串表示移除媒体（连带清空 mediaUrl），其余字段空值跳过不修改。
     */
    public Map<String, String> updateHomeNotice(Map<String, String> patch) {
        Map<String, String> base = new HashMap<>(getHomeNotice());
        boolean changed = false;
        if (patch != null) {
            // mediaType 支持显式清空：包含该 key 即覆盖（空串 = 移除媒体）
            if (patch.containsKey("mediaType")) {
                String t = patch.get("mediaType");
                String norm = (t == null || t.isBlank()) ? "" : t.trim();
                if (!norm.equals(base.get("mediaType"))) {
                    base.put("mediaType", norm);
                    changed = true;
                }
                if (norm.isEmpty()) {
                    base.put("mediaUrl", "");
                }
            }
            for (String key : DEFAULT_HOME_NOTICE.keySet()) {
                if ("version".equals(key) || "mediaType".equals(key)) {
                    continue; // version 只能后端自增；mediaType 已在上方特判
                }
                String v = patch.get(key);
                if (v != null && !v.isBlank()) {
                    String trimmed = v.trim();
                    if (!trimmed.equals(base.get(key))) {
                        base.put(key, trimmed);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            int ver = 1;
            try {
                ver = Integer.parseInt(base.getOrDefault("version", "1")) + 1;
            } catch (NumberFormatException ignore) {
                // 版本号脏数据：重置为 2，前端会重新弹
            }
            base.put("version", String.valueOf(ver));
        }
        redis.opsForHash().putAll(REDIS_KEY_HOME_NOTICE, base);
        redis.expire(REDIS_KEY_HOME_NOTICE, java.time.Duration.ofDays(90));
        log.info("站点文案 home-notice 更新: {}", base);
        return base;
    }
}
