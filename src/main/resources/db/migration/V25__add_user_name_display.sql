-- 积分榜展示名偏好：由用户自己在「我的」里选择显示昵称还是真实姓名（只影响自己那一行）
ALTER TABLE `user`
  ADD COLUMN `name_display` TINYINT NOT NULL DEFAULT 1 COMMENT '积分榜展示名：0真实姓名 1昵称（昵称为空时回退姓名）' AFTER `nickname`;
