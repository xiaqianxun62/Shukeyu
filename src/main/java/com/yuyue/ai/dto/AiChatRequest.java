package com.yuyue.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 一次提问
 */
@Data
@Schema(description = "AI 问答请求")
public class AiChatRequest {

    @Schema(description = "会话 ID；为空表示新建会话")
    private Long conversationId;

    @NotBlank(message = "问题不能为空")
    @Size(max = 1000, message = "问题过长，请控制在 1000 字以内")
    @Schema(description = "用户问题", example = "双打积分怎么算？")
    private String message;
}
