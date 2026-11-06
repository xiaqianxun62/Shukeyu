-- ============================================================
-- AI 调用计量表
--
-- 每次真正打到模型的调用记一行（成功、失败都记）：
--   success = 1 时 prompt/completion/total_tokens 有值，用于算成本与效果；
--   success = 0 时只有 error_type，用于定位「是上游挂了还是被限流」。
--
-- 不记录提示词与模型回复原文：一是表会迅速膨胀，二是问答内容可能包含
-- 用户个人数据，原文只留在会话表（ai_message，后续版本）里。
--
-- user_id 允许为 NULL：定时任务（如运营月报）没有具体操作人。
-- ============================================================

CREATE TABLE `ai_usage_log` (
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`           BIGINT      DEFAULT NULL COMMENT '调用者用户 ID；系统任务为 NULL',
    `scene`             VARCHAR(32) NOT NULL COMMENT '业务场景：CHAT 问答 / TRAINING_PLAN 训练计划 / MATCH_SUGGEST 匹配理由 / REPORT 运营报告',
    `provider`          VARCHAR(32) DEFAULT NULL COMMENT '供应商标识（openai 兼容协议下统一为 openai）',
    `model`             VARCHAR(64) DEFAULT NULL COMMENT '实际模型名，取上游返回，取不到时回退配置值',
    `prompt_tokens`     INT         NOT NULL DEFAULT 0 COMMENT '输入 token 数',
    `completion_tokens` INT         NOT NULL DEFAULT 0 COMMENT '输出 token 数',
    `total_tokens`      INT         NOT NULL DEFAULT 0 COMMENT '合计 token 数',
    `latency_ms`        INT         NOT NULL DEFAULT 0 COMMENT '端到端耗时（毫秒，含重试）',
    `success`           TINYINT     NOT NULL DEFAULT 1 COMMENT '1 成功 0 失败',
    `error_type`        VARCHAR(64) DEFAULT NULL COMMENT '失败时的异常类型 / HTTP 状态码摘要',
    `create_time`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
    PRIMARY KEY (`id`),
    KEY `idx_ai_usage_user_time` (`user_id`, `create_time`),
    KEY `idx_ai_usage_scene_time` (`scene`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用计量：每次模型调用一行（含失败）';
