-- 球场名字唯一约束改由应用层维护
--
-- 原约束 uk_court_name(name) 只约束名字：球场被逻辑删除后行还在表里，再新建同名场地
-- 就直接撞唯一键，接口抛 500——而「删掉再建回来」是完全合理的操作。
--
-- 试过改成 (name, delete_flag) 唯一，但仍有洞：同名场地「删除 → 重建 → 再删除」时，
-- 第二次删除会把第二条记录也置成 delete_flag=1，两行同样撞键，删除动作本身失败。
-- MySQL / MariaDB 没有部分索引（WHERE delete_flag = 0），所以唯一性只能落在应用层：
-- CourtController 的 checkNameUnique 只校验未删除行，配合「删除前引用检查」，
-- 管理端低频操作足以避免重名。
--
-- 这里把唯一索引换成普通索引：查询/校验仍走索引，只是不再由数据库强制唯一。
ALTER TABLE `court` DROP INDEX `uk_court_name`;
ALTER TABLE `court` ADD INDEX `idx_court_name` (`name`);
