package com.yuyue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * RBAC 权限码（接口级权限，形如 game:manage）
 */
@Data
@TableName("`permission`")
public class Permission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限码，形如 模块:动作 */
    private String code;

    /** 展示名 */
    private String name;
}
