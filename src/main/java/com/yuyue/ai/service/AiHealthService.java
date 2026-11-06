package com.yuyue.ai.service;

import com.yuyue.ai.config.AiProperties;
import com.yuyue.ai.dto.AiHealthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 健康检查。
 * <p>
 * 判断顺序：业务总开关 → 容器里有没有 ChatModel → 需要时真实调用一次模型。
 * 全部异常在此吞掉并转成 DOWN 状态，保证健康检查接口本身永远不会 500 —— 否则监控会把
 * 「AI 不可用」误报成「后端挂了」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiHealthService {

    /** 探活用的最小提示词：只要一个词，成本可忽略 */
    private static final String PING_PROMPT = "请只回复 pong";

    /** 从上游异常信息里抓 「HTTP 401」 这类状态码 */
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP\\s+(\\d{3})");

    private final AiProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final Environment environment;

    public AiHealthResponse check(boolean ping) {
        boolean apiKeyConfigured = !environment
                .getProperty("spring.ai.openai.api-key", "")
                .isBlank();
        ChatModel chatModel = chatModelProvider.getIfAvailable();

        String status;
        String message;
        if (!properties.isEnabled()) {
            status = "DISABLED";
            message = "AI 未启用（yuyue.ai.enabled=false），未发起任何模型调用";
        } else if (chatModel == null) {
            status = "UNCONFIGURED";
            message = "AI 已开启但容器中没有 ChatModel：请确认 AI_CHAT_PROVIDER=openai 且 AI_API_KEY 非空";
        } else {
            status = "UP";
            message = "ChatModel 已就绪";
        }

        Long latencyMs = null;
        if (ping) {
            if (chatModel == null) {
                message += "；未探活：无可用 ChatModel";
            } else if (!properties.isHealthPingEnabled()) {
                message += "；未探活：yuyue.ai.health-ping-enabled=false";
            } else {
                long start = System.currentTimeMillis();
                try {
                    String reply = ChatClient.create(chatModel)
                            .prompt()
                            .user(PING_PROMPT)
                            .call()
                            .content();
                    latencyMs = System.currentTimeMillis() - start;
                    message += "；探活成功，模型回复：" + abbreviate(reply);
                } catch (Exception e) {
                    latencyMs = System.currentTimeMillis() - start;
                    status = "DOWN";
                    // 完整原因（含上游返回体）只进日志；这是公开接口，不回显上游错误正文
                    log.warn("AI 健康探活失败: {} - {}", e.getClass().getSimpleName(), rootMessage(e));
                    message += "；探活失败：" + summarizeFailure(e) + "，详见服务端日志";
                }
            }
        }

        return AiHealthResponse.builder()
                .enabled(properties.isEnabled())
                .provider(properties.getProvider())
                .chatModel(properties.getChatModel())
                .chatModelPresent(chatModel != null)
                .apiKeyConfigured(apiKeyConfigured)
                .status(status)
                .latencyMs(latencyMs)
                .message(message)
                .checkedAt(LocalDateTime.now())
                .build();
    }

    /** 模型回复可能很长，健康检查里只留前 40 个字符 */
    private String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "(空)";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "...";
    }

    /** 对外只暴露「哪一类失败」，如 HTTP 401 / HTTP 404 / 超时，具体正文留在日志里 */
    private String summarizeFailure(Throwable e) {
        Matcher m = HTTP_STATUS.matcher(rootMessage(e));
        if (m.find()) {
            return "HTTP " + m.group(1);
        }
        if (e instanceof TimeoutException) {
            return "请求超时";
        }
        return e.getClass().getSimpleName();
    }

    /** 取最内层异常信息，避免把整条调用链写进响应 */
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
