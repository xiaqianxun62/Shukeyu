-- 计分日志：每一次比分提交都留痕。
-- 首次提交比分的用户即本场裁判（action=1）；之后每次改分记录改前/改后比分（action=2）。
CREATE TABLE IF NOT EXISTS `match_score_log` (
  `id`           BIGINT   NOT NULL AUTO_INCREMENT,
  `match_id`     BIGINT   NOT NULL COMMENT '对阵 match_game.id',
  `game_id`      BIGINT   NOT NULL COMMENT '所属球局',
  `operator_id`  BIGINT   NOT NULL COMMENT '提交比分的用户（裁判 / 修改人）',
  `action`       TINYINT  NOT NULL DEFAULT 1 COMMENT '1 首次计分（裁判） 2 修改比分',
  `score_a`      INT      NOT NULL COMMENT '本次提交的 A 队比分',
  `score_b`      INT      NOT NULL COMMENT '本次提交的 B 队比分',
  `prev_score_a` INT      DEFAULT NULL COMMENT '改前 A 队比分（首次计分为 NULL）',
  `prev_score_b` INT      DEFAULT NULL COMMENT '改前 B 队比分（首次计分为 NULL）',
  `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_match` (`match_id`),
  KEY `idx_game` (`game_id`)
) ENGINE = InnoDB COMMENT '计分日志（谁当裁判、录了什么分、谁改的）';
