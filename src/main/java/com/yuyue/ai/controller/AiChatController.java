package com.yuyue.ai.controller;

import com.yuyue.ai.dto.AiChatRequest;
import com.yuyue.ai.dto.AiConversationDetail;
import com.yuyue.ai.dto.AiConversationSummary;
import com.yuyue.ai.service.AiChatService;
import com.yuyue.ai.service.AiConversationService;
import com.yuyue.common.ApiResponse;
import com.yuyue.web.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 智能问答与会话管理。
 * <p>
 * {@code POST /ai/chat} 返回 SSE 流，帧顺序固定：{@code meta → delta* → done}，
 * 失败时是 {@code meta? → error}。事件名与载荷结构见 AiStreamXxx。
 * <p>
 * 前端注意：EventSource 只能发 GET 且不能带 Authorization 头，所以这里用 POST + fetch 流式读取
 * （小程序用 wx.request 的 enableChunked）；不要用 EventSource。
 */
@RestController
@RequestMapping("ai")
@RequiredArgsConstructor
@Tag(name = "AI 智能问答", description = "规则答疑 / 数据查询 / 多轮会话（SSE 流式）")
public class AiChatController {

    private final AiChatService chatService;
    private final AiConversationService conversationService;

    @Operation(summary = "提问（SSE 流式）",
            description = "事件：meta（conversationId / citations / tools）→ delta（增量文本）→ done 或 error")
    @PostMapping(value = "chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AiChatRequest request) {
        return chatService.chat(UserContext.require(), request);
    }

    @Operation(summary = "我的会话列表", description = "按最后消息时间倒序，最多 30 条")
    @GetMapping("conversations")
    public ApiResponse<List<AiConversationSummary>> conversations() {
        return ApiResponse.ok(conversationService.list(UserContext.require()));
    }

    @Operation(summary = "会话详情", description = "会话元信息 + 最近 100 条消息；不属于当前用户返回 404")
    @GetMapping("conversations/{id}")
    public ApiResponse<AiConversationDetail> conversation(@PathVariable Long id) {
        return ApiResponse.ok(conversationService.detail(UserContext.require(), id));
    }

    @Operation(summary = "删除会话", description = "软删除；消息保留但不再返回")
    @DeleteMapping("conversations/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        conversationService.delete(UserContext.require(), id);
        return ApiResponse.ok();
    }
}
