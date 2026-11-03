package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接口根路径：访问 http://localhost:8080/api/ 时给出入口指引，
 * 避免直接撞上登录拦截器返回 401（根路径本来就没有对应业务接口）。
 */
@RestController
@Tag(name = "入口", description = "服务入口与常用地址")
public class RootController {

    @Operation(summary = "服务入口", description = "返回 Swagger 文档地址与常用接口入口；无需登录")
    @GetMapping("/")
    public ApiResponse<Map<String, Object>> index() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "yuyue-backend");
        data.put("description", "数科羽 shukeyu - 校园羽毛球球局系统后端");
        data.put("swaggerUi", "/api/swagger-ui.html");
        data.put("openApiJson", "/api/v3/api-docs");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("登录", "POST /api/auth/login（需图形验证码）");
        entries.put("注册", "POST /api/auth/register（需图形验证码）");
        entries.put("当前用户", "GET /api/auth/me");
        entries.put("球局列表", "GET /api/games（公开）");
        entries.put("积分榜", "GET /api/ranking（公开）");
        entries.put("赛事列表", "GET /api/tournaments（公开）");
        data.put("entries", entries);
        return ApiResponse.ok(data);
    }
}
