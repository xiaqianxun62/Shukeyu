package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * RBAC 角色（四级权限体系：MEMBER 社员 / OFFICER 干事 / PRESIDENT 社长 / ADMIN 系统管理员）
 */
@Data
@TableName("`role`")
public class Role {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码，业务代码只认这个 */
    private String code;

    /** 展示名 */
    private String name;

    /** 排序（越大权限越高） */
    private Integer sort;
}
