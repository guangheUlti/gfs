package com.guanghe.fs.system.domain;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.guanghe.fs.framework.orm.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限实体类
 *
 * @Author: guangheUlti
 * @Date: 2026/3/30 10:11
 */
@Data
@Table("sys_permission")
@EqualsAndHashCode(callSuper = true)
public class SysPermission extends BaseEntity {

    /**
     * 自增id
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 权限编码
     */
    private String permissionCode;

    /**
     * 权限名称
     */
    private String permissionName;

    /**
     * 模块
     */
    private String module;

    /**
     * 权限描述
     */
    private String description;

    /**
     * 排序
     */
    private Integer sort;
}