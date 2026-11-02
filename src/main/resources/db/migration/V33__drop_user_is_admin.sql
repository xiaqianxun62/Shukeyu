-- 旧二值管理员标志废弃：角色分配在 user_role，由系统管理员在后台维护
ALTER TABLE `user` DROP COLUMN `is_admin`;
