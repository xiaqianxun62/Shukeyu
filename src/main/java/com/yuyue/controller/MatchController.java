package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.common.OperationLog;
import com.yuyue.dto.MatchReportRequest;
import com.yuyue.dto.MatchScoreRequest;
import com.yuyue.dto.MatchScoreResponse;
import com.yuyue.service.MatchService;
import com.yuyue.web.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对局：上报结果（ELO 积分同步结算）
 */
@RestController
@RequestMapping("matches")
@RequiredArgsConstructor
@Tag(name = "对局结果", description = "上报对局结果，ELO 同步结算")
public class MatchController {

    private final MatchService matchService;

    @Operation(summary = "上报对局结果", description = "返回 matchId，积分在同一请求内同步结算完成")
    @PostMapping
    @OperationLog("上报对局结果")
    public ApiResponse<Map<String, Long>> report(@Valid @RequestBody MatchReportRequest req) {
        Long operatorId = UserContext.require();
        Long matchId = matchService.report(req, operatorId);
        return ApiResponse.ok(Map.of("matchId", matchId));
    }

    /**
     * 现场计分：给编排好的一场对阵录比分，胜方由比分自动判定。
     * 打完全部对阵后球局自动结束。允许重复提交（改分），但只结算一次积分。
     */
    @Operation(summary = "现场计分", description = "录入某场对阵的比分，胜方自动判定；全部打完球局自动结束。首次提交者记为本场裁判，改分全程留痕；提交 0:0 表示撤销本场（误点救回），不计输赢并退回已结算的积分")
    @PutMapping("{id}/score")
    @OperationLog("现场计分")
    public ApiResponse<MatchScoreResponse> score(@PathVariable Long id,
                                                 @Valid @RequestBody MatchScoreRequest req) {
        Long operatorId = UserContext.require();
        return ApiResponse.ok(matchService.score(id, req, operatorId));
    }
}
