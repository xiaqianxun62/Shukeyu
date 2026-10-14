-- ============================================================
-- V27：修复赛事表软删除列名（delete_flag → deleted）
--
-- 背景：
--   V26 首次应用时，tournament / tournament_entry 的软删除列建成了 delete_flag
--   （沿用了 court 表的命名），但实体类按 MyBatis-Plus 全局配置
--   （mybatis-plus.global-config.db-config.logic-delete-field: deleted）
--   声明的是 deleted。结果 MyBatis 生成的 SELECT 字段列表里带上了 deleted，
--   而库里没有这一列，所有赛事接口直接报：
--     Unknown column 'deleted' in 'field list'
--
-- 处理：
--   只重命名列，不动数据、不动索引定义。之所以不直接改 V26，是因为
--   spring.flyway.validate-on-migrate=false + CREATE TABLE IF NOT EXISTS，
--   已应用的 V26 不会再被执行，V26 原文只能作为「新库建表」的参考。
--
-- 幂等：每条语句都先用 information_schema 判断当前列状态，
--       已经是 deleted 的表会走 no-op 分支，因此本迁移重跑也安全；
--       四张表互不影响，个别表已是 deleted 不会导致整份迁移失败。
-- ============================================================

-- ------------------------------------------------------------
-- tournament：先归零历史 NULL（下面 CHANGE 成 NOT NULL 的前提），再改名
-- ------------------------------------------------------------
SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament'
        AND COLUMN_NAME = 'deleted') = 0
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament'
        AND COLUMN_NAME = 'delete_flag') = 1,
    'UPDATE `tournament` SET `delete_flag` = 0 WHERE `delete_flag` IS NULL',
    'SELECT 1')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament'
        AND COLUMN_NAME = 'deleted') = 0
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament'
        AND COLUMN_NAME = 'delete_flag') = 1,
    'ALTER TABLE `tournament` CHANGE COLUMN `delete_flag` `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''0 正常 1 已删除''',
    'SELECT 1')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- tournament_entry：同上（该表带 active_marker 生成列，改名不影响 uk_entry_active）
-- ------------------------------------------------------------
SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry'
        AND COLUMN_NAME = 'deleted') = 0
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry'
        AND COLUMN_NAME = 'delete_flag') = 1,
    'UPDATE `tournament_entry` SET `delete_flag` = 0 WHERE `delete_flag` IS NULL',
    'SELECT 1')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry'
        AND COLUMN_NAME = 'deleted') = 0
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry'
        AND COLUMN_NAME = 'delete_flag') = 1,
    'ALTER TABLE `tournament_entry` CHANGE COLUMN `delete_flag` `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''0 正常 1 已删除（用户撤回报名）''',
    'SELECT 1')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;