package com.guanghe.fs.system.domain;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import com.guanghe.fs.framework.orm.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作空间实体类
 *
 * @Author: guangheUlti
 * @Date: 2026/3/30 10:11
 */
@Data
@Table("sys_workspace")
@EqualsAndHashCode(callSuper = true)
public class SysWorkspace extends BaseEntity {

    /**
     * 自增id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.ulid)
    private String id;

    /**
     * 工作空间名称
     */
    private String name;

    /**
     * URL友好的唯一标识
     */
    private String slug;

    /**
     * 描述
     */
    private String description;

    /**
     * 所有者id
     */
    private String ownerId;

    /**
     * 成员数量
     */
    private Integer memberCount;
}
