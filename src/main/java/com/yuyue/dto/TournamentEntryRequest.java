package com.yuyue.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 赛事报名
 *
 * <p>「一个参赛单位 = 一行」：
 * <ul>
 *   <li>单打：只传 format（1），partnerId 必须为空；</li>
 *   <li>男双 / 女双 / 混双：必须传 partnerId，由队长（本登录用户）代搭档一起提交。</li>
 * </ul>
 * 搭档性别是否满足组别要求，由 TournamentService 查 user.gender 校验。
 */
@Data
public class TournamentEntryRequest {

    /** 1单打 2男双 3女双 4混双 */
    @NotNull(message = "请选择参赛组别")
    private Integer format;

    /** 双打搭档的 userId；单打留空 */
    private Long partnerId;

    /** 联系电话（可选） */
    @Size(max = 32, message = "联系电话不能超过 32 位")
    private String contact;
}