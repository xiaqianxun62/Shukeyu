package com.yuyue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 赛事计分
 *
 * <p>赛制口径是「点胜方为主、比分可选填」：
 * winner 必填（1 A队胜 / 2 B队胜），是唯一的胜负判定依据；
 * scoreA / scoreB 可选，仅作赛果留档（21 分制单局），不参与胜负判定。
 * <p>
 * 提交 winner=0 表示撤销本场结果（误点救回），本场回到待录入，且不计任何名次。
 */
@Data
public class TournamentScoreRequest {

    /** 1 A队胜 2 B队胜 0 撤销本场 */
    @NotNull(message = "请选择胜方")
    private Integer winner;

    /** 可选比分 A（0-99） */
    @Min(value = 0, message = "比分不能为负")
    @Max(value = 99, message = "比分不能超过 99")
    private Integer scoreA;

    /** 可选比分 B（0-99） */
    @Min(value = 0, message = "比分不能为负")
    @Max(value = 99, message = "比分不能超过 99")
    private Integer scoreB;
}