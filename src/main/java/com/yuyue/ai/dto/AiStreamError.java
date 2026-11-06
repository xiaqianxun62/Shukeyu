package com.yuyue.ai.dto;

/**
 * SSE 错误帧。
 * <p>
 * 注意：错误是「事件」而不是 HTTP 状态码——SSE 一旦开始写响应，状态码已经发出去了，
 * 只能靠帧里的 code 让前端区分「未启用(6001)」「配额用尽(6002)」等原因。
 *
 * @param code    业务错误码，与 ApiResponse.code 同一套
 * @param message 可直接展示的提示文案
 */
public record AiStreamError(int code, String message) {
}
