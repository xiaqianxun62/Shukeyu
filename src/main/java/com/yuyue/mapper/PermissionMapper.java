package com.yuyue.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyue.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RBAC 权限码 Mapper：除基础 CRUD 外，提供按用户聚合权限码 / 角色编码的连接查询
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /** 某用户的全部权限码（user → user_role → role_permission → permission） */
    @Select("""
            SELECT p.code
            FROM permission p
            JOIN role_permission rp ON rp.permission_id = p.id
            JOIN user_role ur ON ur.role_id = rp.role_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectPermissionCodesByUserId(@Param("userId") Long userId);

    /** 某用户的全部角色编码（高权限排前，用于取「主角色」展示名） */
    @Select("""
            SELECT r.code
            FROM role r
            JOIN user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            ORDER BY r.sort DESC
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /** 当前持有 ADMIN 角色的用户数（末位管理员保护用） */
    @Select("""
            SELECT COUNT(*)
            FROM user_role ur
            JOIN role r ON r.id = ur.role_id
            WHERE r.code = 'ADMIN'
            """)
    long countAdminHolders();
}
