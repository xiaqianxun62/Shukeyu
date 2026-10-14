package com.yuyue.service;

import com.yuyue.common.Constants;
import com.yuyue.config.EloProperties;
import com.yuyue.entity.Registration;
import com.yuyue.entity.User;
import com.yuyue.mapper.RegistrationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报名快照的读取与刷新。
 *
 * <p>{@code registration.rating / registration.gender} 是报名那一刻从 user 表复制过去的快照
 * （见 GameService 报名逻辑）。此后球友打过的比赛会改 {@code user.rating}、改资料会改
 * {@code user.gender}，快照随即过期。
 *
 * <p>编排（轮排 / 自动编排）完全依赖这两项：积分决定「势均力敌」怎么配对，
 * 性别决定谁能进男双场 / 女双场 / 混双场。直接拿过期快照编排会开错场地、组错对手，
 * 而且已落库的对阵不存当时用的积分，事后无从纠正——所以必须在生成编排表之前取最新值。
 *
 * <p>本类把「user 表优先、快照兜底」这条口径收敛到一处，避免各读取点各写一套判断而再次漂移。
 * 以 user 表为准即可：ELO 结算时 {@code user.rating} 与 Redis 榜单在同一事务里一起更新，
 * 两者一致，不需要再单独查榜单。
 */
@Service
@RequiredArgsConstructor
public class RegistrationSnapshotService {

    private final RegistrationMapper registrationMapper;
    private final EloProperties eloProperties;

    /**
     * 生成编排表前，把每人的报名快照刷新成 user 表里的当前值。
     *
     * <p>快照语义因此从「报名那一刻」变成「最近一次编排时」——原始的报名时水平仍可从
     * {@code rating_history} 流水追溯，保留一份会不断过期的快照反而更容易被误当成当前值。
     *
     * <p>顺带把内存里的 reg 对象也改了，避免后续代码又读回旧值。
     *
     * @return 实际发生更新的条数
     */
    public int refresh(List<Registration> regs, Map<Long, User> userMap) {
        int updated = 0;
        for (Registration reg : regs) {
            User u = userMap.get(reg.getUserId());
            if (u == null) {
                continue;
            }
            Integer rating = u.getRating();
            Integer gender = u.getGender();
            boolean ratingStale = rating != null && !rating.equals(reg.getRating());
            // 只在 user 性别是明确的男/女时刷新：报名时已强制校验性别必填，
            // 出现「未知」属于异常资料，不该拿它去覆盖报名时那个有效值
            boolean genderStale = gender != null
                    && gender != Constants.GENDER_UNKNOWN
                    && !gender.equals(reg.getGender());
            if (!ratingStale && !genderStale) {
                continue;
            }
            // 只带真正变化的字段落库，避免把 reg 上的其它字段一起写回去
            Registration patch = new Registration();
            patch.setId(reg.getId());
            if (ratingStale) {
                patch.setRating(rating);
                reg.setRating(rating);
            }
            if (genderStale) {
                patch.setGender(gender);
                reg.setGender(gender);
            }
            registrationMapper.updateById(patch);
            updated++;
        }
        return updated;
    }

    /**
     * 当前应使用的 ELO：user 表最新值 → 报名快照 → 平台默认分。
     *
     * <p>第三级兜底不是可有可无的：编排引擎的 Player.rating 是 int 基本类型，
     * 传 0 会让队伍均分算成 0，把所有人当成极端值，编排结果直接失真。
     */
    public int ratingOf(Registration reg, User user) {
        if (user != null && user.getRating() != null) {
            return user.getRating();
        }
        if (reg != null && reg.getRating() != null) {
            return reg.getRating();
        }
        return eloProperties.getDefaultRating();
    }

    /**
     * 当前应使用的性别：user 表最新值 → 报名快照 → 未知。
     *
     * <p>同样不能返回空：编排引擎按性别分池挑人，未知性别的人进不了任何专属场地。
     */
    public int genderOf(Registration reg, User user) {
        if (user != null && user.getGender() != null
                && user.getGender() != Constants.GENDER_UNKNOWN) {
            return user.getGender();
        }
        if (reg != null && reg.getGender() != null) {
            return reg.getGender();
        }
        return Constants.GENDER_UNKNOWN;
    }

    /**
     * 批量取当前性别：userId → 性别，供轮排 / 对阵判定两队构成使用。
     * 跳过性别未知的球友，调用方拿到的一定是可用的 1 / 2。
     */
    public Map<Long, Integer> genderMap(List<Registration> regs, Map<Long, User> userMap) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (Registration reg : regs) {
            int gender = genderOf(reg, userMap.get(reg.getUserId()));
            if (gender != Constants.GENDER_UNKNOWN) {
                out.put(reg.getUserId(), gender);
            }
        }
        return out;
    }
}
