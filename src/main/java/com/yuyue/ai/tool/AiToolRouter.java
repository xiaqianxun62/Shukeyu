package com.yuyue.ai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据工具的意图路由。
 * <p>
 * 规则很简单：按注入顺序问每个工具「这个问题你管吗」，最多取前两个。
 * 之所以限制为两个：一次提问里同时问「我的积分和最近球局」的情况确实存在，但再多就说明
 * 用户在闲聊或问题太泛，硬塞事实只会让回答跑题、还白烧 token。
 * <p>
 * 任何工具抛异常都只记 WARN 并跳过——数据工具是「锦上添花」，查库失败不应该让整轮问答失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiToolRouter {

    private static final int MAX_TOOLS = 2;

    private final List<AiDataTool> tools;

    public AiToolOutcome route(Long userId, String question) {
        if (userId == null || question == null || question.isBlank()) {
            return AiToolOutcome.empty();
        }
        List<String> names = new ArrayList<>();
        StringBuilder facts = new StringBuilder();
        for (AiDataTool tool : tools) {
            if (names.size() >= MAX_TOOLS) {
                break;
            }
            if (!tool.supports(question)) {
                continue;
            }
            try {
                String text = tool.fetch(userId);
                if (text == null || text.isBlank()) {
                    continue;
                }
                facts.append("【").append(tool.label()).append("】\n")
                        .append(text.strip()).append('\n');
                names.add(tool.name());
            } catch (Exception e) {
                log.warn("AI 数据工具执行失败，已跳过 tool={} error={}: {}",
                        tool.name(), e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return names.isEmpty() ? AiToolOutcome.empty() : new AiToolOutcome(List.copyOf(names), facts.toString());
    }
}
