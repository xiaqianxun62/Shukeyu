-- 补齐数据库列注释（仅加 COMMENT，不改类型/默认值/数据）。
-- 覆盖 V1 建表时未写注释的列、V7 avatar、V29 分类 ELO 列、V15 court 时间列。
--
-- 已执行的迁移文件不可修改（Flyway checksum），故用本迁移统一补齐。
-- 范围控制：
--   - 跳过各表 id 主键列（AUTO_INCREMENT 主键 MODIFY 无注释价值、风险大于收益）；
--   - 跳过 registration.active_marker（生成列，MODIFY 需重写生成表达式，避免兼容性风险）；
--   - 未列入的列均已有注释（V2/V11/V14/V20-V23/V25/V28/V30 等）。

-- ============ user 用户表 ============
ALTER TABLE `user`
    MODIFY `win_count`     INT          NOT NULL DEFAULT 0 COMMENT '总胜场（全局综合）',
    MODIFY `loss_count`    INT          NOT NULL DEFAULT 0 COMMENT '总负场（全局综合）',
    MODIFY `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常 1已删除',
    MODIFY `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    MODIFY `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    MODIFY `avatar`        VARCHAR(500) NULL COMMENT '个人头像 URL（/uploads/xxx）',
    -- 分类 ELO（V29 建列时未写注释）
    MODIFY `rating_singles`  INT NOT NULL DEFAULT 1200 COMMENT '单打 ELO 积分',
    MODIFY `singles_win`     INT NOT NULL DEFAULT 0    COMMENT '单打胜场',
    MODIFY `singles_loss`    INT NOT NULL DEFAULT 0    COMMENT '单打负场',
    MODIFY `singles_played`  INT NOT NULL DEFAULT 0    COMMENT '单打场次',
    MODIFY `rating_doubles`  INT NOT NULL DEFAULT 1200 COMMENT '双打 ELO 积分（同性别：男双+女双）',
    MODIFY `doubles_win`     INT NOT NULL DEFAULT 0    COMMENT '双打胜场（同性别）',
    MODIFY `doubles_loss`    INT NOT NULL DEFAULT 0    COMMENT '双打负场（同性别）',
    MODIFY `doubles_played`  INT NOT NULL DEFAULT 0    COMMENT '双打场次（同性别）',
    MODIFY `rating_mixed`    INT NOT NULL DEFAULT 1200 COMMENT '混双 ELO 积分',
    MODIFY `mixed_win`       INT NOT NULL DEFAULT 0    COMMENT '混双胜场',
    MODIFY `mixed_loss`      INT NOT NULL DEFAULT 0    COMMENT '混双负场',
    MODIFY `mixed_played`    INT NOT NULL DEFAULT 0    COMMENT '混双场次';

-- ============ game 球局表 ============
ALTER TABLE `game`
    MODIFY `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常 1已删除',
    MODIFY `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    MODIFY `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ============ registration 报名表 ============
ALTER TABLE `registration`
    MODIFY `game_id`     BIGINT   NOT NULL COMMENT '所属球局',
    MODIFY `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报名时间';

-- ============ match_game 对局表 ============
ALTER TABLE `match_game`
    MODIFY `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '对局创建时间',
    MODIFY `settle_time` DATETIME DEFAULT NULL COMMENT '结算时间，未结算为 NULL';

-- ============ rating_history ELO 流水表 ============
ALTER TABLE `rating_history`
    MODIFY `user_id`       BIGINT NOT NULL COMMENT '用户 ID',
    MODIFY `match_id`      BIGINT NOT NULL COMMENT '所属对局 match_game.id',
    MODIFY `rating_before` INT    NOT NULL COMMENT '变动前积分（该对局类别口径）',
    MODIFY `rating_after`  INT    NOT NULL COMMENT '变动后积分（该对局类别口径）',
    MODIFY `delta`         INT    NOT NULL COMMENT '本次变动量（含分差加成）',
    MODIFY `k_factor`      INT    NOT NULL COMMENT '本场 K 因子',
    MODIFY `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '结算时间';

-- ============ court 球场字典表 ============
ALTER TABLE `court`
    MODIFY `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
