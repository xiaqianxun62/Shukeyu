package com.yuyue.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyue.entity.Role;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /** 给用户授予角色（已存在时忽略） */
    @Insert("INSERT IGNORE INTO `user_role` (`user_id`, `role_id`) VALUES (#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /** 撤销用户的某个角色 */
    @Delete("DELETE FROM `user_role` WHERE `user_id` = #{userId} AND `role_id` = #{roleId}")
    int deleteUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
