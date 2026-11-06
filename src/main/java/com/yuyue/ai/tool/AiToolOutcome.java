package com.yuyue.ai.tool;

import java.util.List;

/**
 * 一轮提问里数据工具的执行结果。
 *
 * @param names 实际执行的工具标识（按执行顺序）
 * @param facts 汇总后的事实文本，注入提示词；没有命中工具时为空串
 */
public record AiToolOutcome(List<String> names, String facts) {

    public static AiToolOutcome empty() {
        return new AiToolOutcome(List.of(), "");
    }

    public boolean hasFacts() {
        return facts != null && !facts.isBlank();
    }
}
