package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.common.OperationLog;
import com.yuyue.dto.TournamentBracketResponse;
import com.yuyue.dto.TournamentCreateRequest;
import com.yuyue.dto.TournamentDetailResponse;
import com.yuyue.dto.TournamentEntryRequest;
import com.yuyue.dto.TournamentListItem;
import com.yuyue.dto.TournamentMatchesResponse;
import com.yuyue.dto.TournamentMatchItem;
import com.yuyue.dto.TournamentScoreRequest;
import com.yuyue.service.TournamentService;
import com.yuyue.web.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 大型比赛（锦标赛）
 *
 * <p>与球局接口最大的区别：<b>完全不涉及ELO</b>——报名、分组、对阵、名次都不读写 user.rating，
 * 也不产生 rating_history 记录。
 *
 * <ul>
 *   <li>GET  /tournaments                赛事广场（登录可选，登录会标记「我报名的」）</li>
 *   <li>GET  /tournaments/{id}           赛事详情（含各组别报名情况、我的参赛单位、领奖台）</li>
 *   <li>GET  /tournaments/{id}/bracket   对阵图（小组积分榜 + 淘汰赛逐轮 + 名次）</li>
 *   <li>GET  /tournaments/{id}/matches   赛程录入（待录入 / 已出结果两栏）</li>
 *   <li>POST /tournaments                创建赛事（管理员）</li>
 *   <li>POST /tournaments/{id}/entries   报名（双打由队长代搭档提交）</li>
 *   <li>DELETE /tournaments/{id}/entries/{entryId} 撤回报名 / 移出参赛名单</li>
 *   <li>POST /tournaments/{id}/draw      抽签分组 + 生成小组赛程（管理员 / 创建人）</li>
 *   <li>PUT  /tournaments/matches/{matchId}/score    录赛果（参赛方 / 管理员 / 创建人）</li>
 *   <li>PUT  /tournaments/{id}/cancel    取消赛事（管理员 / 创建人）</li>
 * </ul>
 */
@Tag(name = "大型比赛", description = "小组抽签 + 逐轮淘汰赛 + 季军赛 / 决赛，不涉及ELO")
@RestController
@RequestMapping("/tournaments")
@RequiredArgsConstructor
public class TournamentController {

    private final TournamentService tournamentService;

    /* ==================== 查询 ==================== */

    @GetMapping
    @Operation(summary = "赛事广场", description = "报名中的赛事排前面；登录后会标记哪些是我已报名的")
    public ApiResponse<List<TournamentListItem>> list() {
        return ApiResponse.ok(tournamentService.list(UserContext.get()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "赛事详情", description = "含各组别报名情况、我的参赛单位与领奖台")
    public ApiResponse<TournamentDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(tournamentService.detail(id, UserContext.get()));
    }

    @GetMapping("/{id}/bracket")
    @Operation(summary = "对阵图", description = "小组赛积分榜 + 淘汰赛逐轮 + 最终名次")
    public ApiResponse<TournamentBracketResponse> bracket(@PathVariable Long id) {
        return ApiResponse.ok(tournamentService.bracket(id, UserContext.get()));
    }

    @GetMapping("/{id}/matches")
    @Operation(summary = "赛程录入", description = "按组别返回待录入 / 已出结果两栏对阵")
    public ApiResponse<TournamentMatchesResponse> matches(@PathVariable Long id) {
        return ApiResponse.ok(tournamentService.matches(id, UserContext.get()));
    }

    /* ==================== 管理 ==================== */

    @PostMapping
    @Operation(summary = "创建赛事（管理员）",
            description = "groupSize 每组队数、advancePerGroup 每组晋级名额；两者可配置，"
                    + "淘汰赛规模 = 组数 × 名额，抽签时会校验是否为 2 的幂")
    @OperationLog("创建赛事")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody TournamentCreateRequest req) {
        Long operatorId = UserContext.require();
        return ApiResponse.ok(Map.of("tournamentId", tournamentService.create(req, operatorId)));
    }

    @PostMapping("/{id}/draw")
    @Operation(summary = "抽签分组", description = "随机打散分组并生成组内单循环赛程；仅限报名中，可重抽前需先取消")
    @OperationLog("赛事抽签")
    public ApiResponse<Map<String, Object>> draw(@PathVariable Long id) {
        Long operatorId = UserContext.require();
        return ApiResponse.ok(tournamentService.draw(id, operatorId));
    }

    @PutMapping("/{id}/cancel")
    @Operation(summary = "取消赛事", description = "只能取消还没开打的赛事")
    @OperationLog("取消赛事")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        Long operatorId = UserContext.require();
        tournamentService.cancel(id, operatorId);
        return ApiResponse.ok();
    }

    /* ==================== 报名 ==================== */

    @GetMapping("/partners/lookup")
    @Operation(summary = "按账号找搭档",
            description = "双打报名需要指定搭档，但没有「搜用户」能力，所以用注册账号精确匹配；"
                    + "只回传姓名 / 头像 / 性别，够球友确认没选错人")
    public ApiResponse<TournamentMatchItem.MemberBrief> lookupPartner(@RequestParam String account) {
        return ApiResponse.ok(tournamentService.lookupPartner(account, UserContext.require()));
    }

    @PostMapping("/{id}/entries")
    @Operation(summary = "赛事报名",
            description = "一个参赛单位 = 一行：单打只传 format；双打必须传 partnerId，由队长代搭档提交")
    @OperationLog("赛事报名")
    public ApiResponse<Map<String, Long>> entry(@PathVariable Long id,
                                                @Valid @RequestBody TournamentEntryRequest req) {
        Long userId = UserContext.require();
        return ApiResponse.ok(Map.of("entryId", tournamentService.entry(id, req, userId)));
    }

    @DeleteMapping("/{id}/entries/{entryId}")
    @Operation(summary = "撤回报名 / 移出参赛名单", description = "报名中可自行撤回；已抽签仅管理员可移出")
    @OperationLog("撤回赛事报名")
    public ApiResponse<Void> cancelEntry(@PathVariable Long id, @PathVariable Long entryId) {
        Long userId = UserContext.require();
        tournamentService.cancelEntry(id, entryId, userId);
        return ApiResponse.ok();
    }

    /* ==================== 计分 ==================== */

    @PutMapping("/matches/{matchId}/score")
    @Operation(summary = "赛事计分",
            description = "只需指定胜方（1=A队2=B队），比分可选填仅作留档；winner=0 撤销本场，"
                    + "会连带清空后续轮次并把参赛单位状态回退")
    @OperationLog("赛事计分")
    public ApiResponse<Map<String, Object>> score(@PathVariable Long matchId,
                                                 @Valid @RequestBody TournamentScoreRequest req) {
        Long operatorId = UserContext.require();
        return ApiResponse.ok(tournamentService.score(matchId, req, operatorId));
    }
}