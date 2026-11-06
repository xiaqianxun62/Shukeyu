package com.yuyue.ai.gateway;

import com.yuyue.ai.config.AiProperties;
import com.yuyue.ai.dto.AiChatResult;
import com.yuyue.ai.service.AiUsageService;
import com.yuyue.common.ErrorCode;
import com.yuyue.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AI 网关的六条核心约束：
 * 1) 正常返回带 token 计量，并写一条成功用量；
 * 2) 总开关关闭时快速失败，不碰模型、不碰 Redis；
 * 3) 配额超限直接拒绝，不发起调用；
 * 4) 瞬时错误重试一次后成功，只写一条成功用量；
 * 5) 非瞬时错误不重试，写失败用量；
 * 6) 连续失败达阈值后熔断，后续请求不再打到模型；超时按失败处理且不重试。
 */
@ExtendWith(MockitoExtension.class)
class AiGatewayTest {

    @Mock private ObjectProvider<ChatModel> chatModelProvider;
    @Mock private ObjectProvider<StreamingChatModel> streamingModelProvider;
    @Mock private ChatModel chatModel;
    @Mock private StreamingChatModel streamingChatModel;
    @Mock private AiQuotaService quotaService;
    @Mock private AiUsageService usageService;

    private AiProperties properties;
    private AiGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setMaxConcurrency(4);
        properties.setTimeoutSeconds(5);
        properties.setDailyQuota(3);
        properties.setMaxAttempts(2);
        properties.setRetryBackoffMs(1);
        properties.setFailureThreshold(3);
        properties.setOpenSeconds(60);
        gateway = new AiGateway(properties, chatModelProvider, streamingModelProvider,
                quotaService, usageService, new AiCircuitBreaker(properties));
    }

    private ChatResponse reply(String text, int promptTokens, int completionTokens) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder()
                        .model("deepseek-chat")
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build())
                .build();
    }

    private ChatResponse chunk(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    /** 收集流式回调，避免每个用例重复写匿名类 */
    private static final class Collector implements AiStreamCallback {
        final List<String> deltas = new ArrayList<>();
        AiChatResult done;
        BizException failure;

        @Override
        public void onDone(AiChatResult result) {
            this.done = result;
        }

        @Override
        public void onFailure(BizException error) {
            this.failure = error;
        }
    }

    @Test
    @DisplayName("流式调用：逐段回调增量，结束时拼出完整正文并写成功用量")
    void streamsDeltas() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(streamingModelProvider.getIfAvailable()).thenReturn(streamingChatModel);
        when(streamingChatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk("发球"), chunk("要过腰")));

        Collector collector = new Collector();
        gateway.streamChat(AiScene.CHAT, 1L, "你是助手", "怎么发球",
                collector.deltas::add, collector);

        assertEquals(List.of("发球", "要过腰"), collector.deltas);
        assertNotNull(collector.done);
        assertEquals("发球要过腰", collector.done.content());
        assertNull(collector.failure);
        verify(quotaService).consume(1L, 3);
        verify(usageService).recordSuccess(eq(1L), eq(AiScene.CHAT), anyString(), any(AiChatResult.class));
    }

    @Test
    @DisplayName("没有流式模型时降级：一次性回调完整回答，仍然只占一次配额")
    void fallsBackToBlockingWhenNoStreamingModel() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(streamingModelProvider.getIfAvailable()).thenReturn(null);
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("降级回答", 4, 2));

        Collector collector = new Collector();
        gateway.streamChat(AiScene.CHAT, 1L, null, "你好", collector.deltas::add, collector);

        assertEquals(List.of("降级回答"), collector.deltas);
        assertNotNull(collector.done);
        assertNull(collector.failure);
        verify(quotaService).consume(1L, 3);
    }

    @Test
    @DisplayName("上游流式报错：回调失败帧，并写一条失败用量")
    void reportsStreamFailure() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(streamingModelProvider.getIfAvailable()).thenReturn(streamingChatModel);
        when(streamingChatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new NonTransientAiException("HTTP 400 Bad Request")));

        Collector collector = new Collector();
        gateway.streamChat(AiScene.CHAT, 1L, null, "你好", collector.deltas::add, collector);

        assertTrue(collector.deltas.isEmpty());
        assertNull(collector.done);
        assertNotNull(collector.failure);
        assertEquals(ErrorCode.AI_UNAVAILABLE.getCode(), collector.failure.getCode());
        assertTrue(collector.failure.getMessage().contains("HTTP 400"));
        verify(usageService).recordFailure(eq(1L), eq(AiScene.CHAT), anyString(),
                eq("NonTransientAiException"), anyLong());
    }

    @Test
    @DisplayName("前端断开：dispose 会取消上游并归还并发许可，不会卡死后续请求")
    void disposesUpstreamOnClientDisconnect() {
        properties.setMaxConcurrency(1);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(streamingModelProvider.getIfAvailable()).thenReturn(streamingChatModel);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(streamingChatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));

        Collector collector = new Collector();
        Disposable disposable = gateway.streamChat(AiScene.CHAT, 1L, null, "你好",
                collector.deltas::add, collector);
        disposable.dispose();

        assertTrue(cancelled.get(), "dispose 应取消上游 Flux");
        // 并发许可只有 1 个：若 cancel 没归还，第二次调用会立刻被拒
        when(streamingChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("好")));
        gateway.streamChat(AiScene.CHAT, 1L, null, "再问", collector.deltas::add, collector);
        assertEquals("好", collector.done.content());
    }

    @Test
    @DisplayName("正常调用：返回正文与 token 计量，写一条成功用量")
    void returnsResultAndRecordsUsage() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("pong", 11, 7));

        AiChatResult result = gateway.chat(AiScene.CHAT, 1L, "你是羽毛球助手", "发球规则是什么");

        assertEquals("pong", result.content());
        assertEquals("deepseek-chat", result.model());
        assertEquals(11, result.promptTokens());
        assertEquals(7, result.completionTokens());
        assertEquals(18, result.totalTokens());
        verify(chatModel, times(1)).call(any(Prompt.class));
        verify(quotaService).consume(1L, 3);
        verify(usageService).recordSuccess(eq(1L), eq(AiScene.CHAT), anyString(), any(AiChatResult.class));
        verify(usageService, never()).recordFailure(any(), any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("总开关关闭：抛 AI_UNAVAILABLE，不碰模型与配额")
    void failsFastWhenDisabled() {
        properties.setEnabled(false);

        BizException e = assertThrows(BizException.class,
                () -> gateway.chat(AiScene.CHAT, 1L, null, "你好"));

        assertEquals(ErrorCode.AI_UNAVAILABLE.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("未启用"));
        verifyNoInteractions(chatModel, quotaService, usageService, chatModelProvider);
    }

    @Test
    @DisplayName("配额用尽：原样抛 AI_QUOTA_EXCEEDED，不发起模型调用")
    void rejectsWhenQuotaExceeded() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        doThrow(new BizException(ErrorCode.AI_QUOTA_EXCEEDED, "今日额度 3 次已用完"))
                .when(quotaService).consume(1L, 3);

        BizException e = assertThrows(BizException.class,
                () -> gateway.chat(AiScene.CHAT, 1L, null, "你好"));

        assertEquals(ErrorCode.AI_QUOTA_EXCEEDED.getCode(), e.getCode());
        verifyNoInteractions(chatModel, usageService);
    }

    @Test
    @DisplayName("瞬时错误：重试一次后成功，只写一条成功用量")
    void retriesTransientThenSucceeds() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new TransientAiException("HTTP 503 Service Unavailable"))
                .thenReturn(reply("pong", 3, 2));

        AiChatResult result = gateway.chat(AiScene.CHAT, 1L, null, "你好");

        assertEquals("pong", result.content());
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(usageService).recordSuccess(eq(1L), eq(AiScene.CHAT), anyString(), any(AiChatResult.class));
        verify(usageService, never()).recordFailure(any(), any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("非瞬时错误（如 401）：不重试，写失败用量，错误只回显状态码")
    void doesNotRetryNonTransient() {
        properties.setMaxAttempts(3);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("HTTP 401 Unauthorized"));

        BizException e = assertThrows(BizException.class,
                () -> gateway.chat(AiScene.CHAT, 1L, null, "你好"));

        assertEquals(ErrorCode.AI_UNAVAILABLE.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("HTTP 401"), "应只回显状态码，实际=" + e.getMessage());
        assertFalse(e.getMessage().contains("Unauthorized"), "不应透出上游错误正文");
        verify(chatModel, times(1)).call(any(Prompt.class));
        verify(usageService).recordFailure(eq(1L), eq(AiScene.CHAT), anyString(),
                eq("NonTransientAiException"), anyLong());
    }

    @Test
    @DisplayName("连续失败达阈值：熔断后直接拒绝，不再调用模型")
    void opensCircuitAfterThreshold() {
        properties.setMaxAttempts(1);
        properties.setFailureThreshold(2);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("HTTP 500 Internal Server Error"));

        assertThrows(BizException.class, () -> gateway.chat(AiScene.CHAT, 1L, null, "第一次"));
        assertThrows(BizException.class, () -> gateway.chat(AiScene.CHAT, 1L, null, "第二次"));

        BizException e = assertThrows(BizException.class,
                () -> gateway.chat(AiScene.CHAT, 1L, null, "第三次"));
        assertTrue(e.getMessage().contains("熔断"), "实际=" + e.getMessage());
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("超时：抛 AI_UNAVAILABLE 且不重试（已消耗完整超时预算）")
    void timesOutWithoutRetry() {
        properties.setTimeoutSeconds(1);
        properties.setMaxAttempts(2);
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(3000);
            return reply("太慢了", 1, 1);
        });

        long start = System.currentTimeMillis();
        BizException e = assertThrows(BizException.class,
                () -> gateway.chat(AiScene.CHAT, 1L, null, "你好"));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(e.getMessage().contains("超时"), "实际=" + e.getMessage());
        verify(chatModel, times(1)).call(any(Prompt.class));
        assertTrue(elapsed < 2500, "1 秒超时应快速返回，实际耗时 " + elapsed + "ms");
        verify(usageService).recordFailure(eq(1L), eq(AiScene.CHAT), anyString(), anyString(), anyLong());
    }
}
