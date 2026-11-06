-- ============================================================
-- AI 智能问答：会话与消息
--
-- 为什么要存会话而不是「一问一答」：
--   1) 追问要上下文（「那混双呢？」必须知道上一句在说积分规则）；
--   2) 用户要能翻历史，小程序/官网同源；
--   3) 出了问题能复盘：知识库命中什么、用了哪个数据工具，都记在消息行上。
--
-- 原文只留在这里，不进 ai_usage_log（那张表只放计量），避免统计表被长文本撑爆。
-- citations / tools 用逗号分隔的轻量文本，不引 JSON 依赖：
--   它们只用于展示「参考了哪几条规则」，不需要按字段查询。
-- ============================================================

CREATE TABLE `ai_conversation` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT      NOT NULL COMMENT '归属用户',
    `title`           VARCHAR(64) NOT NULL COMMENT '会话标题：取首条提问前 30 字',
    `message_count`   INT         NOT NULL DEFAULT 0 COMMENT '消息条数（含提问与回答）',
    `last_message_at` DATETIME    DEFAULT NULL COMMENT '最后一条消息时间，用于会话列表排序',
    `deleted`         TINYINT     NOT NULL DEFAULT 0 COMMENT '0 正常 1 已删除（软删除）',
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话';

-- 列表查询永远带 user_id + deleted，按时间倒序；索引按这个顺序建才用得上
CREATE INDEX `idx_ai_conv_user` ON `ai_conversation` (`user_id`, `deleted`, `last_message_at`);

CREATE TABLE `ai_message` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT      NOT NULL COMMENT '所属会话',
    `user_id`         BIGINT      NOT NULL COMMENT '归属用户（冗余，便于按用户清理）',
    `role`            VARCHAR(16) NOT NULL COMMENT 'USER 提问 / ASSISTANT 回答',
    `content`         MEDIUMTEXT  NOT NULL COMMENT '消息正文',
    `citations`       TEXT        DEFAULT NULL COMMENT '命中知识库条目，分号分隔，如 1:ELO 积分规则·K 值',
    `tools`           VARCHAR(128) DEFAULT NULL COMMENT '本次回答调用的数据工具，逗号分隔，如 MY_RATING',
    `total_tokens`    INT         NOT NULL DEFAULT 0 COMMENT '本次回答 token 数（流式下常为 0）',
    `latency_ms`      INT         NOT NULL DEFAULT 0 COMMENT '本次回答耗时（毫秒）',
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话消息';

-- 取历史消息固定是「某会话按 id 升序」，索引直接覆盖
CREATE INDEX `idx_ai_msg_conv` ON `ai_message` (`conversation_id`, `id`);
