ALTER TABLE `user` ADD COLUMN `avatar` VARCHAR(500) NULL;
ALTER TABLE `user` ADD COLUMN `anonymous_avatar_id` BIGINT NULL COMMENT '匿名头像ID';
