package com.yuyue.ai.tool;

/**
 * 问答用的数据工具：把「需要查库才能答」的问题变成一段事实文本，随提示词一起给模型。
 * <p>
 * 为什么不用模型 function calling：上游是 OpenAI 兼容协议，各家对 tools/tool_calls 的支持程度
 * 不一致（有的直接报 400，有的只回半截 JSON）。这里的查询只有三个、意图又很明确，用关键词路由
 * 判定反而更稳、更快、更省 token，而且路由逻辑可以单测——模型不可用时也不影响「查我的积分」。
 * <p>
 * 约束：工具只读，不写库；查询失败由路由层吞掉并记日志，绝不让一次统计查询拖垮整轮问答。
 */
public interface AiDataTool {

    /** 工具标识，落库与前端展示用（如 MY_RATING） */
    String name();

    /** 中文标签，注入提示词与 SSE meta，让用户知道这次查了什么 */
    String label();

    /** 是否处理这个问题 */
    boolean supports(String question);

    /**
     * 查询事实文本（多行 Markdown 列表）。
     *
     * @param userId 当前用户；匿名/系统调用为 null，此时返回空串
     * @return 事实文本；查不到时返回空串（不要编造）
     */
    String fetch(Long userId);
}
