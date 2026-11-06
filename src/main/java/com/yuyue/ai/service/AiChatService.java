package com.yuyue.ai.service;

import com.yuyue.ai.dto.AiChatRequest;
import com.yuyue.ai.dto.AiChatResult;
import com.yuyue.ai.dto.AiCitation;
import com.yuyue.ai.dto.AiStreamDelta;
import com.yuyue.ai.dto.AiStreamDone;
import com.yuyue.ai.dto.AiStreamError;
import com.yuyue.ai.dto.AiStreamMeta;
import com.yuyue.ai.gateway.AiGateway;
import com.yuyue.ai.gateway.AiScene;
import com.yuyue.ai.gateway.AiStreamCallback;
import com.yuyue.ai.kb.AiKnowledgeBase;
import com.yuyue.ai.kb.AiKnowledgeHit;
import com.yuyue.ai.tool.AiToolOutcome;
import com.yuyue.ai.tool.AiToolRouter;
import com.yuyue.common.ErrorCode;
import com.yuyue.entity.AiConversation;
import com.yuyue.entity.AiMessage;
import com.yuyue.exception.BizException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 智能问答的编排：会话 → 检索 → 查数 → 提示词 → 流式输出 → 落库。
 * <p>
 * 为什么流式不用 {@code Flux<ServerSentEvent>}：项目是 Spring MVC（servlet）栈，引 WebFlux
 * 只为一路 SSE 不划算，{@link SseEmitter} 足够，且能和现有拦截器、鉴权、全局异常处理共存。
 * <p>
 * 两个线程纪律：
 * <ul>
 *   <li>SSE 写操作直接在模型 I/O 线程上做——它本来就异步，再派线程只会打乱帧顺序；</li>
 *   <li>落库放到独立小线程池：MySQL 写入几毫秒到几十毫秒，不该占住 Reactor 线程，
 *       也不该让「保存失败」变成「用户看不到已经生成好的回答」。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    /** SSE 连接寿命：模型超时 20s + 重试，120s 足够，超时由容器断开 */
    private static final long SSE_TIMEOUT_MS = 120_000L;
    /** 送进提示词的知识库条目数：3 条基本覆盖一个问题，再多会挤掉历史与数据 */
    private static final int KB_LIMIT = 3;
    /** 带上的历史消息条数（含提问与回答），约等于最近 3 轮 */
    private static final int HISTORY_LIMIT = 6;

    private final AiGateway gateway;
    private final AiKnowledgeBase knowledgeBase;
    private final AiToolRouter toolRouter;
    private final AiPromptBuilder promptBuilder;
    private final AiConversationService conversationService;

    /** 落库专用线程；daemon 线程不阻止应用退出 */
    private final ExecutorService persistPool = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "ai-chat-persist");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    void shutdown() {
        persistPool.shutdown();
    }

    /**
     * 处理一次提问，返回可直接交给 Spring MVC 的 SSE 通道。
     * <p>
     * 注意：所有失败都以 {@code error} 事件返回，而不是 HTTP 状态码——SSE 开始写响应后状态码已经发出。
     */
    public SseEmitter chat(Long userId, AiChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicReference<Disposable> upstream = new AtomicReference<>();

        // 前端关闭页面 / 网络断开时必须取消上游：否则模型还在生成，token 照烧
        emitter.onTimeout(() -> cancel(upstream));
        emitter.onError(e -> cancel(upstream));
        emitter.onCompletion(() -> cancel(upstream));

        try {
            start(userId, request, emitter, upstream);
        } catch (BizException e) {
            fail(emitter, e);
        } catch (Exception e) {
            log.error("AI 问答启动失败 userId={}", userId, e);
            fail(emitter, new BizException(ErrorCode.SYSTEM_ERROR));
        }
        return emitter;
    }

    private void start(Long userId, AiChatRequest request, SseEmitter emitter,
                       AtomicReference<Disposable> upstream) {
        String question = request.getMessage() == null ? "" : request.getMessage().strip();
        if (question.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "问题不能为空");
        }

        AiConversation conversation = request.getConversationId() == null
                ? conversationService.create(userId, question)
                : conversationService.requireOwned(userId, request.getConversationId());

        // 先取历史再写本轮提问，否则历史里会多出一条「当前问题」，等于让模型自己抄自己
        List<AiMessage> history = conversationService.history(conversation.getId(), HISTORY_LIMIT);
        conversationService.appendUser(conversation.getId(), userId, question);

        List<AiKnowledgeHit> hits = knowledgeBase.search(question, KB_LIMIT);
        AiToolOutcome outcome = toolRouter.route(userId, question);
        List<AiCitation> citations = toCitations(hits);

        // meta 必须先于任何 delta：前端要靠它拿到新会话 ID 与引用角标
        sendOrThrow(emitter, "meta",
                new AiStreamMeta(conversation.getId(), conversation.getTitle(), citations, outcome.names()));

        Disposable disposable = gateway.streamChat(
                AiScene.CHAT, userId,
                promptBuilder.systemPrompt(hits, outcome),
                promptBuilder.userPrompt(question, history),
                delta -> sendOrThrow(emitter, "delta", new AiStreamDelta(delta)),
                new AiStreamCallback() {
                    @Override
                    public void onDone(AiChatResult result) {
                        persistAndFinish(emitter, conversation.getId(), userId, result, citations, outcome);
                    }

                    @Override
                    public void onFailure(BizException error) {
                        fail(emitter, error);
                    }
                });
        upstream.set(disposable);
    }

    /** 回答落库后才发 done：前端收到 done 就能刷新会话列表，不会出现「列表里还没有这条」 */
    private void persistAndFinish(SseEmitter emitter, Long conversationId, Long userId,
                                  AiChatResult result, List<AiCitation> citations, AiToolOutcome outcome) {
        if (result.content() == null || result.content().isBlank()) {
            fail(emitter, new BizException(ErrorCode.AI_UNAVAILABLE, "模型返回了空内容"));
            return;
        }
        persistPool.execute(() -> {
            Long messageId = null;
            try {
                AiMessage saved = conversationService.appendAssistant(
                        conversationId, userId, result.content().strip(),
                        joinCitations(citations), String.join(",", outcome.names()),
                        result.totalTokens(), (int) Math.min(Math.max(result.latencyMs(), 0), Integer.MAX_VALUE));
                messageId = saved.getId();
            } catch (Exception e) {
                // 回答已经流给用户了，保存失败只能记日志；done 里 messageId 为 null，前端照常展示
                log.error("保存 AI 回答失败 conversationId={}", conversationId, e);
            }
            sendQuietly(emitter, "done",
                    new AiStreamDone(conversationId, messageId, result.totalTokens(), result.latencyMs()));
            completeQuietly(emitter);
        });
    }

    private void fail(SseEmitter emitter, BizException error) {
        log.warn("AI 问答失败 code={} message={}", error.getCode(), error.getMessage());
        sendQuietly(emitter, "error", new AiStreamError(error.getCode(), error.getMessage()));
        completeQuietly(emitter);
    }

    private List<AiCitation> toCitations(List<AiKnowledgeHit> hits) {
        List<AiCitation> citations = new ArrayList<>(hits.size());
        int index = 1;
        for (AiKnowledgeHit hit : hits) {
            citations.add(new AiCitation(index++, hit.chunk().displayTitle(), hit.chunk().doc()));
        }
        return citations;
    }

    /** 引用落库为「1:标题;2:标题」，展示时原样拆开即可 */
    private String joinCitations(List<AiCitation> citations) {
        if (citations.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (AiCitation citation : citations) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(citation.index()).append(':').append(citation.title());
        }
        return sb.toString();
    }

    private void cancel(AtomicReference<Disposable> upstream) {
        Disposable disposable = upstream.getAndSet(null);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    /** delta 推送失败要往上抛，让网关终止上游生成 */
    private void sendOrThrow(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new IllegalStateException("SSE 推送失败: " + e.getMessage(), e);
        }
    }

    /** 收尾帧：连接可能已经断了，失败无需再处理 */
    private void sendQuietly(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 收尾帧发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete 失败: {}", e.getMessage());
        }
    }
}
