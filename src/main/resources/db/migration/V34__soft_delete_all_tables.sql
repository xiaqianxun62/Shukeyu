-- ============================================================
-- V32：全部删除改为软删除
--
-- 背景：
--   此前只有 user / game / registration / tournament / tournament_entry / court
--   六张表带软删除列，剩下四张表仍然是物理 DELETE：
--     - match_game          清空未出结果的对阵（MatchGameWriter.clearPending）
--     - rating_history      撤销对局时删积分流水（MatchSettleService.rollback）
--     - tournament_match    赛程回滚时删后续轮次对阵（TournamentService.rollbackDownstream）
--     - verification_question 管理员删题（VerificationService.delete）
--   物理 DELETE 会把审计线索一并抹掉，出问题无法追责。
--
-- 处理：
--   给这四张表补 deleted 列（0 正常 / 1 已删除），实体类同步加 @TableLogic，
--   MyBatis-Plus 之后会把 delete 改写成 UPDATE deleted=1，SELECT 自动带 deleted=0。
--   已执行的迁移不可修改（Flyway checksum），故用本迁移追加。
--
-- 幂等：每条语句先用 information_schema 判断列是否已存在，
--       已存在的表走 no-op 分支，本迁移重跑也安全。
-- ============================================================

-- ------------------------------------------------------------
-- match_game 对局表
-- ------------------------------------------------------------
SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'match_game'
        AND COLUMN_NAME = 'deleted') = 0,
    'ALTER TABLE `match_game` ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''0 正常 1 已删除'' AFTER `settle_time`',
    'SELECT 1')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- rating_history ELO 积分流水表
-- ------------------------------------------------------------
SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rating_history'
        AND COLUMN_NAME = 'deleted') = 0,
    'ALTER TABLE `rating_history` ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''0 正常 1 已删除'' AFTER `category`',
    'SELECT 1')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- tournament_match 赛程对阵表
-- ------------------------------------------------------------
SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_match'
        AND COLUMN_NAME = 'deleted') = 0,
    'ALTER TABLE `tournament_match` ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''0 正常 1 已删除'' AFTER `referee_id`',
    'SELECT 1')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- verification_question 身份校验问题库
-- ------------------------------------------------------------
SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'verification_question'
        AND COLUMN_NAME = 'deleted') = 0,
    'ALTER TABLE `verification_question` ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''0 正常 1 已删除'' AFTER `enabled`',
    'SELECT 1')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
