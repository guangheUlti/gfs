package com.guanghe.fs.system.domain;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import com.guanghe.fs.framework.orm.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色实体类
 *
 * @Author: guangheUlti
 * @Date: 2026/3/30 10:11
 */
@Data
@Table("sys_role")
@EqualsAndHashCode(callSuper = true)
public class SysRole extends BaseEntity {

    /**
     * 自增id
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 工作空间id
     */
    private String workspaceId;

    /**
     * 角色编码
     */
    private String roleCode;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 角色描述
     */
    private String description;

    /**
     * 角色类型：0 系统预设，1 自定义
     */
    private Integer roleType;
}
