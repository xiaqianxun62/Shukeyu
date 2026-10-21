package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.dto.RankingItem;
import com.yuyue.service.RankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分榜：官网与小程序同源
 */
@RestController
@RequestMapping("ranking")
@RequiredArgsConstructor
@Tag(name = "积分榜", description = "官网与小程序同源")
public class RankingController {

    private final RankingService rankingService;

    @Operation(summary = "积分榜 Top N", description = "type：1单打(默认) 2双打 3混双；积分与胜负均为该类别口径")
    @GetMapping
    public ApiResponse<List<RankingItem>> top(
            @RequestParam(defaultValue = "10") int n,
            @RequestParam(defaultValue = "1") int type) {
        int category = Math.min(Math.max(type, 1), 3);
        return ApiResponse.ok(rankingService.topN(n, category));
    }

    @Operation(summary = "手动重建榜单", description = "从 MySQL 全量重建（只保留打过比赛的用户）")
    @GetMapping("rebuild")
    public ApiResponse<String> rebuild() {
        rankingService.rebuildFromDb();
        return ApiResponse.ok("rebuilt");
    }
}
