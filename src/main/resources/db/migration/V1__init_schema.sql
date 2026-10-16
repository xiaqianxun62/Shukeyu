-- 数科羽 shukeyu 校园羽毛球球局系统 表结构初始化（Flyway V1）
-- 注意：数据库本身由 JDBC 参数 createDatabaseIfNotExist=true 自动创建，
--       Flyway 只负责表结构，因此本脚本不包含 CREATE DATABASE / USE 语句。
-- 文件名规则：V<版本>__<描述>.sql（双下划线），版本号只能递增，已执行的脚本不可修改。

-- 用户表（学号 + 姓名校园认证）
CREATE TABLE IF NOT EXISTS `user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `student_no`    VARCHAR(32)  NOT NULL COMMENT '学号',
  `name`          VARCHAR(32)  NOT NULL COMMENT '姓名',
  `gender`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0未知 1男 2女',
  `college`       VARCHAR(64)  DEFAULT NULL COMMENT '学院',
  `password_hash` VARCHAR(128) NOT NULL COMMENT '密码哈希(SHA-256+盐)',
  `rating`        INT          NOT NULL DEFAULT 1200 COMMENT 'ELO 积分',
  `games_played`  INT          NOT NULL DEFAULT 0 COMMENT '历史场次',
  `win_count`     INT          NOT NULL DEFAULT 0,
  `loss_count`    INT          NOT NULL DEFAULT 0,
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_student_no` (`student_no`)
) ENGINE = InnoDB COMMENT '用户表';

-- 球局表
CREATE TABLE IF NOT EXISTS `game` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT,
  `title`        VARCHAR(64) NOT NULL COMMENT '球局标题',
  `location`     VARCHAR(128) DEFAULT NULL COMMENT '场地',
  `play_date`    DATE        NOT NULL COMMENT '打球日期',
  `start_time`   TIME        NOT NULL COMMENT '开始时间',
  `end_time`     TIME        DEFAULT NULL COMMENT '结束时间',
  `max_players`  INT         NOT NULL DEFAULT 12 COMMENT '人数上限',
  `status`       TINYINT     NOT NULL DEFAULT 0 COMMENT '0报名中 1已编排 2进行中 3已结束 4已取消',
  `creator_id`   BIGINT      NOT NULL COMMENT '发布者',
  `deleted`      TINYINT     NOT NULL DEFAULT 0,
  `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_play_date` (`play_date`, `status`)
) ENGINE = InnoDB COMMENT '球局表';

-- 报名表
CREATE TABLE IF NOT EXISTS `registration` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `game_id`     BIGINT   NOT NULL,
  `user_id`     BIGINT   NOT NULL COMMENT '报名人（匿名对外）',
  `gender`      TINYINT  NOT NULL COMMENT '1男 2女 快照',
  `rating`      INT      NOT NULL COMMENT '报名时积分快照',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_game_user` (`game_id`, `user_id`),
  KEY `idx_game` (`game_id`)
) ENGINE = InnoDB COMMENT '报名表';

-- 对局表（一局比赛 = 一场对阵）
CREATE TABLE IF NOT EXISTS `match_game` (
  `id`             BIGINT   NOT NULL AUTO_INCREMENT,
  `game_id`        BIGINT   NOT NULL COMMENT '所属球局',
  `format`         TINYINT  NOT NULL COMMENT '1单打 2男双 3女双 4混双',
  `team_a`         VARCHAR(64) NOT NULL COMMENT 'A队成员id,逗号分隔',
  `team_b`         VARCHAR(64) NOT NULL COMMENT 'B队成员id,逗号分隔',
  `score_a`        INT      NOT NULL DEFAULT 0 COMMENT 'A队得分',
  `score_b`        INT      NOT NULL DEFAULT 0 COMMENT 'B队得分',
  `winner`         TINYINT  NOT NULL DEFAULT 0 COMMENT '0未定 1A队 2B队',
  `settle_status`  TINYINT  NOT NULL DEFAULT 0 COMMENT '0未结算 1已结算',
  `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `settle_time`    DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_game` (`game_id`)
) ENGINE = InnoDB COMMENT '对局表';

-- ELO 积分变动流水
CREATE TABLE IF NOT EXISTS `rating_history` (
  `id`            BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT   NOT NULL,
  `match_id`      BIGINT   NOT NULL,
  `rating_before` INT      NOT NULL,
  `rating_after`  INT      NOT NULL,
  `delta`         INT      NOT NULL,
  `k_factor`      INT      NOT NULL,
  `expected`      DECIMAL(5,4) NOT NULL COMMENT '期望胜率 E',
  `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_match` (`match_id`)
) ENGINE = InnoDB COMMENT 'ELO 积分流水表';
