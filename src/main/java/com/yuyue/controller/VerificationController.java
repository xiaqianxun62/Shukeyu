package com.yuyue.controller;

import com.yuyue.common.ApiResponse;
import com.yuyue.common.ErrorCode;
import com.yuyue.dto.AnswerSubmitRequest;
import com.yuyue.dto.QuestionCreateRequest;
import com.yuyue.entity.VerificationQuestion;
import com.yuyue.service.PermissionService;
import com.yuyue.service.VerificationService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 身份校验：问题库管理（管理员） + 用户答题
 */
@RestController
@RequestMapping("verification")
@RequiredArgsConstructor
@Tag(name = "身份校验", description = "问题库管理（管理员）+ 用户答题")
public class VerificationController {

    private final VerificationService verificationService;
    private final PermissionService permissionService;

    /** 持 verify:manage 权限（社长 / 系统管理员）方可管理题库 */
    private void requireAdmin(Long userId) {
        permissionService.requirePermission(userId, PermissionService.P_VERIFY_MANAGE);
    }

    // ========== 管理员：问题库 CRUD ==========

    @Operation(summary = "问题库列表", description = "管理员接口；返回全部问题（answer 字段不返回）")
    @GetMapping("questions")
    public ApiResponse<List<VerificationQuestion>> listQuestions() {
        requireAdmin(UserContext.require());
        return ApiResponse.ok(verificationService.listAll());
    }

    @Operation(summary = "新增问题", description = "管理员接口")
    @PostMapping("questions")
    public ApiResponse<VerificationQuestion> createQuestion(@Valid @RequestBody QuestionCreateRequest req) {
        requireAdmin(UserContext.require());
        return ApiResponse.ok(verificationService.create(req));
    }

    @Operation(summary = "编辑问题", description = "管理员接口")
    @PutMapping("questions/{id}")
    public ApiResponse<VerificationQuestion> updateQuestion(@PathVariable Long id,
                                                            @Valid @RequestBody QuestionCreateRequest req) {
        requireAdmin(UserContext.require());
        return ApiResponse.ok(verificationService.update(id, req));
    }

    @Operation(summary = "删除问题", description = "管理员接口")
    @DeleteMapping("questions/{id}")
    public ApiResponse<Void> deleteQuestion(@PathVariable Long id) {
        requireAdmin(UserContext.require());
        verificationService.delete(id);
        return ApiResponse.ok(null);
    }

    // ========== 用户端：答题 ==========

    @Operation(summary = "随机抽一题", description = "用户端：随机返回一道启用的问题（只返回 id + question，不返回 answer）")
    @GetMapping("random")
    public ApiResponse<VerificationQuestion> randomQuestion() {
        Long userId = UserContext.require();
        return ApiResponse.ok(verificationService.randomQuestion(userId));
    }

    @Operation(summary = "提交答案", description = "用户端：比对答案，答对则通过身份校验")
    @PostMapping("submit")
    public ApiResponse<java.util.Map<String, Object>> submit(@Valid @RequestBody AnswerSubmitRequest req) {
        Long userId = UserContext.require();
        boolean correct = verificationService.submitAnswer(userId, req);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("correct", correct);
        body.put("verified", correct); // 答对后 user.isVerified 已更新
        return ApiResponse.ok(body);
    }
}
