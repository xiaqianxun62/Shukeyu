package com.yuyue.common;

import lombok.Getter;

/**
 * 业务错误码
 */
@Getter
public enum ErrorCode {

    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    USER_EXISTS(1001, "账号已注册"),
    USER_NAME_DUPLICATE(1004, "用户昵称已被占用，请换一个"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_ERROR(1003, "账号或密码错误"),
    GAME_FULL(2001, "球局人数已满"),
    GAME_STATUS_ERROR(2002, "球局状态不允许该操作"),
    ALREADY_REGISTERED(2003, "已报名该球局"),
    NOT_REGISTERED(2004, "尚未报名该球局"),
    GAME_CANCELLED(2005, "球局已取消"),
    GAME_TITLE_DUPLICATE(2006, "球局标题已存在，请换一个"),
    MATCH_SETTLED(3001, "对局已结算，不可重复上报"),
    TOURNAMENT_NOT_FOUND(4001, "赛事不存在"),
    TOURNAMENT_STATUS_ERROR(4002, "赛事状态不允许该操作"),
    TOURNAMENT_ALREADY_DRAWN(4003, "已抽签，不能再改报名或重抽"),
    TOURNAMENT_FULL(4004, "该组别报名已满"),
    TOURNAMENT_ENTRY_EXISTS(4005, "你已报名该赛事，或已作为他人搭档报名"),
    TOURNAMENT_PARTNER_INVALID(4006, "搭档不符合该组别要求"),
    TOURNAMENT_SCORE_INVALID(4007, "比分不合法"),
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
