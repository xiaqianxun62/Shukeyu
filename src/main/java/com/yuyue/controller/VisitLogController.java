package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.entity.User;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.UserMapper;
import com.yuyue.service.VisitLogService;
import com.yuyue.web.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 浏览访问日志：管理员查看访问者 IP 与访问轨迹
 */
@RestController
@RequestMapping("visit-logs")
@RequiredArgsConstructor
@Tag(name = "访问日志", description = "记录浏览用户的 IP 地址，仅管理员可查")
public class VisitLogController {

    private final VisitLogService visitLogService;
    private final UserMapper userMapper;

    @Operation(summary = "访问日志列表", description = "仅管理员；按时间倒序，可按 IP 精确过滤，limit 默认 100 上限 500")
    @GetMapping
    public ApiResponse<List<VisitLogService.VisitLogView>> list(
            @RequestParam(required = false) String ip,
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        requireAdmin();
        return ApiResponse.ok(visitLogService.list(ip, limit == null ? 100 : limit));
    }

    private void requireAdmin() {
        Long operatorId = UserContext.require();
        User u = userMapper.selectById(operatorId);
        if (u == null || u.getIsAdmin() == null || u.getIsAdmin() != Constants.ADMIN_YES) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可查看");
        }
    }
}
