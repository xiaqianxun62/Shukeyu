package com.yuyue.ai.gateway;

import com.yuyue.ai.dto.AiChatResult;
import com.yuyue.exception.BizException;

/**
 * 流式调用的结果回调。
 * <p>
 * 约定：{@link #onDelta} 与 {@link #onDone} 在模型 I/O 线程上被调用，实现里只做「往 SSE 写数据」
 * 这类轻量动作，不要跑同步 SQL 长事务——否则会拖住整条流。
 * <p>
 * {@link #onFailure} 只会被调用一次，且此后不会再收到 delta/done。
 */
public interface AiStreamCallback {

    /** 模型开始产出（首段增量到达，或降级路径已拿到完整回复） */
    default void onStart() {
    }

    /**
     * 生成结束。
     *
     * @param result 完整回复与计量；流式路径下 token 可能为 0（见 AiGateway#streamChat 注释）
     */
    void onDone(AiChatResult result);

    /** 失败：AI 未启用、配额用尽、超时、上游报错等，统一是业务异常 */
    void onFailure(BizException error);
}
