package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.dto.StatsOverviewResponse;
import com.yuyue.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页数据概览：总量 + 近 7 日场次 + ELO 分布（公开聚合数据，无个人信息）。
 */
@RestController
@RequestMapping("stats")
@RequiredArgsConstructor
@Tag(name = "数据概览", description = "官网首页数据看板，公开聚合数据")
public class StatsController {

    private final StatsService statsService;

    @Operation(summary = "首页数据概览", description = "公开接口；用户/球局/对局/报名总量、近7日场次、ELO 分布")
    @GetMapping("overview")
    public ApiResponse<StatsOverviewResponse> overview() {
        return ApiResponse.ok(statsService.overview());
    }
}
