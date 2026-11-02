-- RBAC 五表：角色、权限码、用户-角色、角色-权限
-- 四级角色体系（对应研究文档）：MEMBER 社员 / OFFICER 干事 / PRESIDENT 社长 / ADMIN 系统管理员

CREATE TABLE `role` (
    `id`       BIGINT       NOT NULL AUTO_INCREMENT,
    `code`     VARCHAR(32)  NOT NULL COMMENT '角色编码',
    `name`     VARCHAR(32)  NOT NULL COMMENT '角色名称',
    `sort`     INT          NOT NULL DEFAULT 0 COMMENT '排序（越大权限越高）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

CREATE TABLE `permission` (
    `id`   BIGINT      NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(64) NOT NULL COMMENT '权限码，形如 模块:动作',
    `name` VARCHAR(64) NOT NULL COMMENT '权限名称',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限码';

CREATE TABLE `role_permission` (
    `role_id`       BIGINT NOT NULL,
    `permission_id` BIGINT NOT NULL,
    PRIMARY KEY (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限码';

CREATE TABLE `user_role` (
    `user_id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    PRIMARY KEY (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色';

-- 角色种子
INSERT INTO `role` (`code`, `name`, `sort`) VALUES
  ('MEMBER',    '普通社员', 0),
  ('OFFICER',   '社团干事', 1),
  ('PRESIDENT', '社团社长', 2),
  ('ADMIN',     '系统管理员', 3);

-- 权限码种子（接口级最小集，按需在库里续加）
INSERT INTO `permission` (`code`, `name`) VALUES
  ('game:manage',       '球局管理（代管任意球局：编排/计分/隐藏）'),
  ('rotation:manage',   '轮排管理'),
  ('tournament:manage', '赛事管理（创建/抽签/取消/移出选手）'),
  ('court:manage',      '场地字典维护'),
  ('settings:manage',   '站点配置（诗句/公告）'),
  ('user:manage',       '用户与角色分配'),
  ('verify:manage',     'SHUKE 认证与题库管理'),
  ('log:view',          '访问日志查看');

-- 角色 → 权限码
-- 干事：活动类三码
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r JOIN `permission` p
WHERE r.code = 'OFFICER' AND p.code IN ('game:manage', 'rotation:manage', 'tournament:manage');

-- 社长：干事全部 + 用户/认证
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r JOIN `permission` p
WHERE r.code = 'PRESIDENT' AND p.code IN ('game:manage', 'rotation:manage', 'tournament:manage', 'user:manage', 'verify:manage');

-- 系统管理员：全部
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r JOIN `permission` p
WHERE r.code = 'ADMIN';

-- 存量管理员回填为 ADMIN 角色
INSERT INTO `user_role` (`user_id`, `role_id`)
SELECT u.id, r.id FROM `user` u JOIN `role` r
WHERE u.is_admin = 1 AND r.code = 'ADMIN';
