-- ============================================================
-- V35：清理 tournament_match 残留的 delete_flag 列
--
-- 背景：
--   V26 建 tournament_match 时沿用了 court 表的命名，把软删除列建成了
--   delete_flag；V27 修复时只 rename 了 tournament / tournament_entry
--   两张表，漏了 tournament_match。
--   V34 又给 tournament_match 补了标准软删除列 deleted（与全局配置
--   logic-delete-field: deleted 对齐，实体类也映射到 deleted）。
--   结果 tournament_match 同时存在 deleted（在用）和 delete_flag（死列）。
--
-- 处理：删掉 delete_flag，统一用 deleted。
--
-- 幂等：先用 information_schema 判断 delete_flag 是否存在，
--       不存在则走 no-op 分支，重跑也安全。
-- ============================================================

SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_match'
        AND COLUMN_NAME = 'delete_flag') = 1,
    'ALTER TABLE `tournament_match` DROP COLUMN `delete_flag`',
    'SELECT 1')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
