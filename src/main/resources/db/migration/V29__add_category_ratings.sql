-- ELO 按类别拆分：单打 / 同性别双打 / 混双 三套积分与胜负场。
-- 三类初始积分均取现有综合 rating（历史综合分作为三类共同起点）。
-- 原 rating / games_played / win_count / loss_count 保留为全局综合字段。
ALTER TABLE `user`
    ADD COLUMN rating_singles INT NOT NULL DEFAULT 1200,
    ADD COLUMN singles_win     INT NOT NULL DEFAULT 0,
    ADD COLUMN singles_loss    INT NOT NULL DEFAULT 0,
    ADD COLUMN singles_played  INT NOT NULL DEFAULT 0,
    ADD COLUMN rating_doubles  INT NOT NULL DEFAULT 1200,
    ADD COLUMN doubles_win     INT NOT NULL DEFAULT 0,
    ADD COLUMN doubles_loss    INT NOT NULL DEFAULT 0,
    ADD COLUMN doubles_played  INT NOT NULL DEFAULT 0,
    ADD COLUMN rating_mixed    INT NOT NULL DEFAULT 1200,
    ADD COLUMN mixed_win       INT NOT NULL DEFAULT 0,
    ADD COLUMN mixed_loss      INT NOT NULL DEFAULT 0,
    ADD COLUMN mixed_played    INT NOT NULL DEFAULT 0;

UPDATE `user` SET rating_singles = rating, rating_doubles = rating, rating_mixed = rating;
