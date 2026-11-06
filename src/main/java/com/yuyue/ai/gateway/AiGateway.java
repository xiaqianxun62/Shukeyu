package com.yuyue.ai.gateway;

import com.yuyue.ai.config.AiProperties;
import com.yuyue.ai.dto.AiChatResult;
import com.yuyue.ai.service.AiUsageService;
import com.yuyue.common.ErrorCode;
import com.yuyue.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 调用的唯一入口。
 * <p>
 * 所有场景（问答 / 训练计划 / 匹配理由 / 月报）都必须走这里，业务 Service 不直接碰
 * {@link ChatModel}。这样「并发、配额、超时、重试、熔断、计量」只实现一次：
 * <ol>
 *   <li>总开关与模型装配检查 —— 未启用就快速失败，不浪费一次 Redis / DB 往返；</li>
 *   <li>熔断检查 —— 上游连续失败时直接拒绝，避免每个请求都等满超时；</li>
 *   <li>并发信号量 —— 超过上限快速失败，防止 AI 的秒级耗时占满 Tomcat 线程池；</li>
 *   <li>每日配额 —— 按用户计数，超限直接返回「明天再来」；</li>
 *   <li>带超时的调用 + 有限重试 —— 只对瞬时错误重试，鉴权/参数类错误立刻放弃；</li>
 *   <li>计量 —— 成功记 token 与耗时，失败记异常类型，写库失败不影响主流程。</li>
 * </ol>
 * <p>
 * 失败一律转成 {@link ErrorCode#AI_UNAVAILABLE}（或配额专用的
 * {@link ErrorCode#AI_QUOTA_EXCEEDED}），调用方据此降级，不需要认识 Spring AI 的异常体系。
 * <p>
 * 直接调用 {@link ChatModel#call(Prompt)} / {@link StreamingChatModel#stream(Prompt)}
 * 而不是 ChatClient：网关需要原始的 {@link ChatResponse} 才能取到 usage 与上游模型名。
 */
@Slf4j
@Component
public class AiGateway {

    /** 从上游异常信息里抓「HTTP 401」这类状态码，用于给用户一个可读的失败原因 */
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP\\s+(\\d{3})");

    private final AiProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<StreamingChatModel> streamingModelProvider;
    private final AiQuotaService quotaService;
    private final AiUsageService usageService;
    private final AiCircuitBreaker circuitBreaker;
    private final Semaphore permits;
    private final ExecutorService executor;

    public AiGateway(AiProperties properties,
                     ObjectProvider<ChatModel> chatModelProvider,
                     ObjectProvider<StreamingChatModel> streamingModelProvider,
                     AiQuotaService quotaService,
                     AiUsageService usageService,
                     AiCircuitBreaker circuitBreaker) {
        this.properties = properties;
        this.chatModelProvider = chatModelProvider;
        this.streamingModelProvider = streamingModelProvider;
        this.quotaService = quotaService;
        this.usageService = usageService;
        this.circuitBreaker = circuitBreaker;
        this.permits = new Semaphore(Math.max(properties.getMaxConcurrency(), 1));
        // 超时兜底要能中断阻塞调用，所以单独一个线程池；daemon 线程不阻止应用退出
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "ai-gateway-call");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 发起一次非流式模型调用。
     *
     * @param scene        业务场景（决定计量维度）
     * @param userId       调用者；系统任务传 null（不占用户配额）
     * @param systemPrompt 系统提示词，可空
     * @param userPrompt   用户输入，不能为空
     * @return 回复正文 + token 计量
     * @throws BizException AI 未启用 / 并发已满 / 熔断中 / 配额用尽 / 上游失败
     */
    public AiChatResult chat(AiScene scene, Long userId, String systemPrompt, String userPrompt) {
        ChatModel chatModel = requireChatModel();
        enterGuards(userId);
        try {
            return doChat(scene, userId, chatModel, systemPrompt, userPrompt);
        } finally {
            permits.release();
        }
    }

    /**
     * 流式调用：逐段把增量文本交给 {@code onDelta}，结束时回调最终结果。
     * <p>
     * 模型不支持流式（或未装配 StreamingChatModel）时自动降级为「非流式调用 + 一次性
     * 回调一段完整文本」，调用方（SSE 接口）无需区分两条路径。
     * <p>
     * 注意：OpenAI 兼容协议的流式响应通常不带 usage，token 计量会是 0，
     * 只有耗时是准的；要精确成本请查非流式链路或供应商账单。
     *
     * @return 取消句柄；调用 {@link Disposable#dispose()} 会立即停止上游生成并归还并发许可。
     *         降级路径（无需流式）返回一个已释放的句柄，调用方照常 dispose 即可。
     */
    public Disposable streamChat(AiScene scene, Long userId, String systemPrompt, String userPrompt,
                                 Consumer<String> onDelta, AiStreamCallback callback) {
        ChatModel chatModel = requireChatModel();
        StreamingChatModel streamingModel = streamingModelProvider.getIfAvailable();
        if (streamingModel == null) {
            try {
                AiChatResult result = chat(scene, userId, systemPrompt, userPrompt);
                if (!result.content().isEmpty()) {
                    onDelta.accept(result.content());
                }
                callback.onDone(result);
            } catch (BizException e) {
                callback.onFailure(e);
            }
            // 空操作句柄：降级路径是一次性返回，没有可取消的上游订阅
            return () -> { };
        }

        enterGuards(userId);
        return doStream(scene, userId, streamingModel, systemPrompt, userPrompt, onDelta, callback);
    }

    /* ============================ 流式实现 ============================ */

    private Disposable doStream(AiScene scene, Long userId, StreamingChatModel streamingModel,
                                String systemPrompt, String userPrompt,
                                Consumer<String> onDelta, AiStreamCallback callback) {
        long start = System.currentTimeMillis();
        int timeoutSeconds = Math.max(properties.getTimeoutSeconds(), 1);
        StringBuilder buffer = new StringBuilder();
        AtomicBoolean released = new AtomicBoolean(false);
        AtomicReference<String> modelName = new AtomicReference<>(properties.getChatModel());
        AtomicReference<Usage> lastUsage = new AtomicReference<>();

        Prompt prompt = buildPrompt(systemPrompt, userPrompt);
        Flux<ChatResponse> flux = streamingModel.stream(prompt)
                // 按「两段增量之间最长静默」计超时：卡住的连接会在这里断开
                .timeout(Duration.ofSeconds(timeoutSeconds))
                // 前端断开时 subscribe 不会回调 onComplete/onError，许可必须在这里归还，否则泄漏
                .doOnCancel(() -> releaseOnce(released));

        return flux.subscribe(
                chunk -> {
                    String delta = extractText(chunk);
                    if (delta != null && !delta.isEmpty()) {
                        buffer.append(delta);
                        try {
                            onDelta.accept(delta);
                        } catch (Exception e) {
                            // 前端断开（SSE 写失败）：记一笔后终止，避免继续烧 token
                            log.info("SSE 下游已断开，终止本次流式生成: {}", e.getMessage());
                            throw e;
                        }
                    }
                    ChatResponseMetadata metadata = chunk == null ? null : chunk.getMetadata();
                    if (metadata != null) {
                        if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                            modelName.set(metadata.getModel());
                        }
                        if (metadata.getUsage() != null) {
                            lastUsage.set(metadata.getUsage());
                        }
                    }
                },
                error -> {
                    releaseOnce(released);
                    long latency = System.currentTimeMillis() - start;
                    circuitBreaker.onFailure();
                    usageService.recordFailure(userId, scene, properties.getProvider(), errorType(error), latency);
                    log.warn("AI 流式调用失败 scene={} error={}: {}", scene,
                            error.getClass().getSimpleName(), rootMessage(error));
                    callback.onFailure(toBizException(error));
                },
                () -> {
                    releaseOnce(released);
                    long latency = System.currentTimeMillis() - start;
                    Usage usage = lastUsage.get();
                    int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                    int completionTokens = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                    AiChatResult result = new AiChatResult(buffer.toString(), modelName.get(),
                            promptTokens, completionTokens, promptTokens + completionTokens, latency);
                    circuitBreaker.onSuccess();
                    usageService.recordSuccess(userId, scene, properties.getProvider(), result);
                    callback.onDone(result);
                });
    }

    private String extractText(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return "";
        }
        String text = chunk.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private void releaseOnce(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            permits.release();
        }
    }

    /* ============================ 非流式实现 ============================ */

    private AiChatResult doChat(AiScene scene, Long userId, ChatModel chatModel,
                                String systemPrompt, String userPrompt) {
        int maxAttempts = Math.max(properties.getMaxAttempts(), 1);
        long start = System.currentTimeMillis();
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ChatResponse response = callWithTimeout(chatModel, systemPrompt, userPrompt);
                long latency = System.currentTimeMillis() - start;
                AiChatResult result = toResult(response, latency);
                circuitBreaker.onSuccess();
                usageService.recordSuccess(userId, scene, properties.getProvider(), result);
                return result;
            } catch (Exception e) {
                lastError = e;
                boolean retryable = attempt < maxAttempts && isRetryable(e);
                log.warn("AI 调用失败 scene={} attempt={}/{} retryable={} error={}: {}",
                        scene, attempt, maxAttempts, retryable,
                        e.getClass().getSimpleName(), rootMessage(e));
                if (!retryable) {
                    circuitBreaker.onFailure();
                    usageService.recordFailure(userId, scene, properties.getProvider(),
                            errorType(e), System.currentTimeMillis() - start);
                    throw new BizException(ErrorCode.AI_UNAVAILABLE, summarize(e));
                }
                sleepBeforeRetry();
            }
        }
        // 循环正常结束只有一种可能：maxAttempts 被上面的 Math.max 修正，理论不可达
        usageService.recordFailure(userId, scene, properties.getProvider(),
                errorType(lastError), System.currentTimeMillis() - start);
        throw new BizException(ErrorCode.AI_UNAVAILABLE, summarize(lastError));
    }

    /**
     * 带超时的同步调用。
     * <p>
     * {@code cancel(true)} 只能打断等待，底层 HTTP 读阻塞要等 spring.http.client.read-timeout
     * 才会真正释放线程——所以这里是「业务超时」，不是「掐断连接」。
     */
    private ChatResponse callWithTimeout(ChatModel chatModel, String systemPrompt, String userPrompt) throws Exception {
        Prompt prompt = buildPrompt(systemPrompt, userPrompt);
        int timeoutSeconds = Math.max(properties.getTimeoutSeconds(), 1);
        Future<ChatResponse> future = executor.submit(() -> chatModel.call(prompt));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private Prompt buildPrompt(String systemPrompt, String userPrompt) {
        List<Message> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));
        return new Prompt(messages);
    }

    /* ============================ 守卫 ============================ */

    private ChatModel requireChatModel() {
        if (!properties.isEnabled()) {
            throw new BizException(ErrorCode.AI_UNAVAILABLE, "AI 未启用");
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new BizException(ErrorCode.AI_UNAVAILABLE, "未配置可用模型");
        }
        return chatModel;
    }

    /** 熔断 → 并发 → 配额；配额失败要归还信号量，避免泄漏 */
    private void enterGuards(Long userId) {
        if (!circuitBreaker.allowRequest()) {
            throw new BizException(ErrorCode.AI_UNAVAILABLE, "上游连续失败，暂时熔断");
        }
        if (!permits.tryAcquire()) {
            // 快速失败优于排队：AI 是附属功能，不能拖住主业务线程
            throw new BizException(ErrorCode.AI_UNAVAILABLE, "当前使用人数较多，请稍后再试");
        }
        try {
            quotaService.consume(userId, properties.getDailyQuota());
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    /* ============================ 工具方法 ============================ */

    /** 只对瞬时错误重试：鉴权失败、参数错误重试多少次都一样，超时则已消耗完整预算 */
    private boolean isRetryable(Throwable e) {
        if (e instanceof TimeoutException || e instanceof InterruptedException) {
            return false;
        }
        return !(e instanceof NonTransientAiException);
    }

    private void sleepBeforeRetry() {
        long backoff = Math.max(properties.getRetryBackoffMs(), 0L);
        if (backoff == 0L) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AiChatResult toResult(ChatResponse response, long latencyMs) {
        String content = "";
        String model = properties.getChatModel();
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;

        if (response != null) {
            String text = extractText(response);
            if (!text.isEmpty()) {
                content = text;
            }
            ChatResponseMetadata metadata = response.getMetadata();
            if (metadata != null) {
                if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                    model = metadata.getModel();
                }
                Usage usage = metadata.getUsage();
                if (usage != null) {
                    promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                    completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                    totalTokens = usage.getTotalTokens() == null ? promptTokens + completionTokens : usage.getTotalTokens();
                }
            }
        }
        return new AiChatResult(content, model, promptTokens, completionTokens, totalTokens, latencyMs);
    }

    private BizException toBizException(Throwable error) {
        if (error instanceof BizException be) {
            return be;
        }
        if (error instanceof java.util.concurrent.TimeoutException
                || error instanceof io.netty.handler.timeout.ReadTimeoutException
                || (error != null && error.getClass().getSimpleName().contains("Timeout"))) {
            return new BizException(ErrorCode.AI_UNAVAILABLE,
                    "调用超时（" + properties.getTimeoutSeconds() + "s）");
        }
        return new BizException(ErrorCode.AI_UNAVAILABLE, summarize(error));
    }

    /** 失败原因摘要：给接口用，不含上游返回体，避免把供应商错误正文透给前端 */
    private String summarize(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        if (e instanceof TimeoutException) {
            return "调用超时（" + properties.getTimeoutSeconds() + "s）";
        }
        Matcher m = HTTP_STATUS.matcher(rootMessage(e));
        if (m.find()) {
            return "上游返回 HTTP " + m.group(1);
        }
        String msg = rootMessage(e);
        return msg.length() <= 80 ? msg : msg.substring(0, 80) + "...";
    }

    private String errorType(Throwable e) {
        return e == null ? "Unknown" : e.getClass().getSimpleName();
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) {
            return cur.getClass().getSimpleName();
        }
        return msg.length() <= 160 ? msg : msg.substring(0, 160) + "...";
    }
}
