package com.yuyue.dto;

import com.yuyue.entity.Court;

import java.time.LocalDateTime;

/**
 * 管理端球场列表项：场地本身 + 被引用次数。
 * <p>
 * 为什么要带引用次数：球场是字典数据，被球局 / 赛事引用后就不该被删掉——
 * 删了之后 {@code courtMapper.selectById} 受逻辑删除影响查不到，历史球局会丢掉
 * 场地名与经纬度（地图定位直接失效）。管理端要能看到「还在被谁用」，
 * 删除按钮才有依据禁用。
 *
 * @param gameCount       引用该球场的球局数（不含已删除）
 * @param tournamentCount 引用该球场的赛事数（不含已删除）
 */
public record CourtAdminView(
        Long id,
        String name,
        String address,
        java.math.BigDecimal lat,
        java.math.BigDecimal lng,
        Integer sort,
        Integer enabled,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        long gameCount,
        long tournamentCount) {

    public static CourtAdminView of(Court c, long gameCount, long tournamentCount) {
        return new CourtAdminView(c.getId(), c.getName(), c.getAddress(), c.getLat(), c.getLng(),
                c.getSort(), c.getEnabled(), c.getCreateTime(), c.getUpdateTime(),
                gameCount, tournamentCount);
    }

    /** 是否仍被业务数据引用（引用中的球场只能停用，不能删除） */
    public boolean inUse() {
        return gameCount > 0 || tournamentCount > 0;
    }
}
