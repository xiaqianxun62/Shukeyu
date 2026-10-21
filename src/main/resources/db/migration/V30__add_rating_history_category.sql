-- 积分流水记录类别：1单打 2双打 3混双（依 match_game.format 映射）。
ALTER TABLE rating_history
    ADD COLUMN category TINYINT NOT NULL DEFAULT 1 COMMENT '1单打 2双打 3混双';

-- 历史行按对局 format 回填；match 缺失的旧行保持默认 1（单打），回退场景极少，可接受。
UPDATE rating_history h
    JOIN match_game m ON h.match_id = m.id
SET h.category = CASE m.format WHEN 1 THEN 1 WHEN 4 THEN 3 ELSE 2 END;
