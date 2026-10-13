package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 浏览访问日志：记录访问者 IP 地址与访问轨迹
 */
@Data
@TableName("visit_log")
public class VisitLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 访问者 IP（X-Forwarded-For 优先，取首个非 unknown） */
    private String ip;

    /** 登录用户 ID，未登录为 NULL */
    private Long userId;

    /** HTTP 方法 */
    private String method;

    /** 请求路径（含查询串） */
    private String path;

    /** 浏览器 UA */
    private String userAgent;

    /** 来源页 URL */
    private String referer;

    private LocalDateTime createTime;
}
