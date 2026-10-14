-- ELO 结算细化：性别修正 + 局内分差动态调整
-- margin_delta：本场分差加成的部分（胜方正、负方负，与基础分反向对称）
-- gender_adjust：本场所属队伍的性别修正 handicap（正值吃亏、负值占便宜、0 为同赛制/中性）
ALTER TABLE `rating_history`
  ADD COLUMN `margin_delta`  INT          NOT NULL DEFAULT 0 COMMENT '局内分差加成（胜方正、负方负，严格零和）' AFTER `expected`,
  ADD COLUMN `gender_adjust` DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '性别修正 handicap 分（正=吃亏 负=占便宜）' AFTER `margin_delta`;
