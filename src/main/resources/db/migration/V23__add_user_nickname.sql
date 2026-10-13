-- 昵称：积分榜等非姓名场景的对外展示名；为空时前端回退显示真实姓名
ALTER TABLE `user`
  ADD COLUMN `nickname` VARCHAR(32) DEFAULT NULL COMMENT '昵称，积分榜等非姓名场景展示；为空回退真实姓名' AFTER `name`;
