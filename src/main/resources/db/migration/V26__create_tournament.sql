-- ============================================================
-- 大型比赛（锦标赛）模块
--
-- 赛制：小组赛（随机抽签分组、组内单循环）→ 淘汰赛逐轮推进 → 季军赛 + 决赛
-- 组别：单打 / 男双 / 女双 / 混双，各自独立跑完整赛制
--
-- 设计要点：
--   1. 一个 tournament 可同时开设多个组别（format_list），每个组别独立分组、独立产出冠军；
--   2. 小组规模 group_size 与晋级名额 advance_per_group 由管理员创建赛事时配置，
--      淘汰赛规模 = 组数 × 每组名额，需为 2 的幂（4/8/16/32/64/128）才生成得了完整签位；
--   3. 淘汰赛不做「预生成整棵对阵树」，而是每一轮全部出结果后，用本轮胜者随机配对生成下一轮，
--      因此不依赖固定规模，改配置也不用改代码；
--   4. 本模块完全不接 ELO：不写 rating_history、不动 user.rating、不刷积分榜。
-- ============================================================

-- 赛事主体
CREATE TABLE IF NOT EXISTS `tournament` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `title`             VARCHAR(64)  NOT NULL COMMENT '赛事名称',
  `location`          VARCHAR(255) DEFAULT NULL COMMENT '比赛地点',
  `court_id`          BIGINT       DEFAULT NULL COMMENT '关联球场字典 court.id',
  `play_date`         DATE         DEFAULT NULL COMMENT '比赛日期',
  `remark`            VARCHAR(500) DEFAULT NULL COMMENT '赛事说明 / 补充规则（最多 500 字）',
  `format_list`       VARCHAR(32)  NOT NULL DEFAULT '1,2,3,4' COMMENT '开设组别，逗号分隔：1单打 2男双 3女双 4混双',
  `group_size`        INT          NOT NULL DEFAULT 4 COMMENT '小组赛每组队数（组内单循环，至少 2）',
  `advance_per_group` INT          NOT NULL DEFAULT 1 COMMENT '每组晋级名额（至少 1，且需小于 group_size）',
  `max_entries`       INT          NOT NULL DEFAULT 64 COMMENT '单个组别的参赛队数上限（双打按队计）',
  `status`            TINYINT      NOT NULL DEFAULT 0 COMMENT '0 报名中 1 已抽签 2 进行中 3 已结束 4 已取消',
  `champion_entry_id` BIGINT       DEFAULT NULL COMMENT '冠军参赛单位 tournament_entry.id',
  `runner_up_entry_id` BIGINT      DEFAULT NULL COMMENT '亚军参赛单位',
  `third_entry_id`    BIGINT       DEFAULT NULL COMMENT '季军参赛单位',
  `fourth_entry_id`   BIGINT       DEFAULT NULL COMMENT '第 4 名（季军赛负者）',
  `creator_id`        BIGINT       NOT NULL COMMENT '创建人（管理员）',
  `deleted`           TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常 1 已删除',
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_status` (`status`, `play_date`),
  KEY `idx_creator` (`creator_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '大型比赛赛事主体';

-- 参赛单位（报名 = 一行）。单打 1 人、双打 2 人（user_id 为队长/提交人，partner_id 为搭档）。
-- 小组赛战绩与最终名次都挂在参赛单位上，不再单独建「小组成员」表。
CREATE TABLE IF NOT EXISTS `tournament_entry` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `tournament_id`    BIGINT      NOT NULL COMMENT '所属赛事',
  `format`           TINYINT     NOT NULL COMMENT '组别：1单打 2男双 3女双 4混双',
  `user_id`          BIGINT      NOT NULL COMMENT '队长（提交报名的人）',
  `partner_id`       BIGINT      DEFAULT NULL COMMENT '双打搭档 user.id；单打为空',
  `contact`          VARCHAR(32) DEFAULT NULL COMMENT '联系电话（可选）',
  `seed_no`          INT         DEFAULT NULL COMMENT '抽签号（抽签后回填，1..N）',
  `group_id`         BIGINT      DEFAULT NULL COMMENT '抽签后所在小组 tournament_group.id',
  `group_no`         VARCHAR(8)  DEFAULT NULL COMMENT '组名 A / B / C...',
  `group_win`        INT         NOT NULL DEFAULT 0 COMMENT '小组赛胜场',
  `group_loss`       INT         NOT NULL DEFAULT 0 COMMENT '小组赛负场',
  `group_point_diff` INT         NOT NULL DEFAULT 0 COMMENT '小组赛净胜分（胜方加分负方减分，用于同胜场排序）',
  `group_rank`       INT         DEFAULT NULL COMMENT '小组名次（1 = 小组第一）',
  `advance`          TINYINT     NOT NULL DEFAULT 0 COMMENT '1 已晋级淘汰赛 0 未晋级',
  `status`           TINYINT     NOT NULL DEFAULT 0 COMMENT '0 已报名 1 已抽签 2 仍在赛 3 已淘汰',
  `eliminated_stage` TINYINT     DEFAULT NULL COMMENT '止步阶段：1小组赛 2淘汰赛首轮 3季军赛 4决赛',
  `final_rank`       INT         DEFAULT NULL COMMENT '最终名次：1冠军 2亚军 3季军 4第4，其余为并列区间起点',
  `active_marker`    BIGINT      DEFAULT NULL COMMENT '生成列：未删除时恒为 1，供软删除兼容的唯一键使用',
  `deleted`          TINYINT     NOT NULL DEFAULT 0 COMMENT '0 正常 1 已删除（用户撤回报名）',
  `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_entry_active` (`tournament_id`, `format`, `user_id`, `active_marker`),
  KEY `idx_group` (`group_id`),
  KEY `idx_partner` (`tournament_id`, `partner_id`),
  KEY `idx_tournament` (`tournament_id`, `format`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '比赛参赛单位（报名记录）';

-- 小组（抽签后生成，一个小组只属于一个组别）
CREATE TABLE IF NOT EXISTS `tournament_group` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `tournament_id` BIGINT      NOT NULL COMMENT '所属赛事',
  `format`        TINYINT     NOT NULL COMMENT '组别：1单打 2男双 3女双 4混双',
  `group_no`      VARCHAR(8)  NOT NULL COMMENT '组名 A / B / C...',
  `seq`           INT         NOT NULL COMMENT '组序号，从 1 开始，用于排序与展示',
  `entry_count`   INT         NOT NULL DEFAULT 0 COMMENT '组内实际参赛队数（最后一组可能不满）',
  `status`        TINYINT     NOT NULL DEFAULT 0 COMMENT '0 未开始 1 进行中 2 已完赛',
  `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_group` (`tournament_id`, `format`, `group_no`),
  KEY `idx_tournament` (`tournament_id`, `format`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '比赛小组（随机抽签分组）';

-- 对阵：小组赛组内单循环 + 逐轮淘汰赛 + 季军赛 / 决赛，统一一张表。
-- stage：1 小组赛 2 淘汰赛 3 季军赛 4 决赛
-- round_no：小组赛=组内轮次；淘汰赛=第几轮（1 起）
-- round_size：淘汰赛本轮参赛队数（32/16/8/4），前端据此显示「三十二强 / 十六强 / 八强 / 四强」；小组赛=组内队数
CREATE TABLE IF NOT EXISTS `tournament_match` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `tournament_id` BIGINT      NOT NULL COMMENT '所属赛事',
  `format`        TINYINT     NOT NULL COMMENT '组别：1单打 2男双 3女双 4混双',
  `group_id`      BIGINT      DEFAULT NULL COMMENT '小组赛所属小组；淘汰赛为空',
  `stage`         TINYINT     NOT NULL COMMENT '1 小组赛 2 淘汰赛 3 季军赛 4 决赛',
  `round_no`      INT         NOT NULL DEFAULT 1 COMMENT '小组赛=组内轮次；淘汰赛=第几轮',
  `round_size`    INT         NOT NULL DEFAULT 0 COMMENT '本轮参赛队数 / 组内队数',
  `match_no`      INT         NOT NULL DEFAULT 1 COMMENT '同轮次内的场次序号（1 起）',
  `court`         INT         DEFAULT NULL COMMENT '场地号，从 1 开始',
  `team_a`        BIGINT      DEFAULT NULL COMMENT 'A 队参赛单位 tournament_entry.id',
  `team_b`        BIGINT      DEFAULT NULL COMMENT 'B 队参赛单位',
  `winner`        TINYINT     NOT NULL DEFAULT 0 COMMENT '0 未出结果 1 A队胜 2 B队胜',
  `score_a`       SMALLINT    DEFAULT NULL COMMENT '可选比分 A（21 分制，仅作记录，不参与判定）',
  `score_b`       SMALLINT    DEFAULT NULL COMMENT '可选比分 B',
  `referee_id`    BIGINT      DEFAULT NULL COMMENT '计分人（首次提交比分的用户）',
  `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_round` (`tournament_id`, `format`, `stage`, `round_no`),
  KEY `idx_group` (`group_id`, `round_no`),
  KEY `idx_teams` (`team_a`, `team_b`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '比赛对阵（小组赛 + 淘汰赛）';

-- 关于 active_marker：未删除时恒为 1、删除后为 NULL。配合 uk_entry_active 保证
--   - 同一赛事同一组别，同一个人只能作为队长报名一次（并发重复插入报 DuplicateKey）；
--   - 撤回报名后再次报名不受影响（NULL 不参与唯一比较）。
-- 之所以不用「deleted=0 时等于 id」的写法：MariaDB 禁止生成列引用 AUTO_INCREMENT 列（Error 1901）。