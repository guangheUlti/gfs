package com.guanghe.fs.system.domain;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.guanghe.fs.framework.orm.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 功能开关实体类
 *
 * @Author: guangheUlti
 * @Date: 2026/9/12
 */
@Data
@Table("sys_feature_toggle")
@EqualsAndHashCode(callSuper = true)
public class SysFeatureToggle extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 自增id
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 功能标识（见 FeatureKeys）
     */
    private String featureKey;

    /**
     * 是否开启
     */
    private Boolean enabled;
}
