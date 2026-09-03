package com.guanghe.fs.storage.plugin.core.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 插件元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginMetadata {

    /**
     * 平台名称
     */
    private String name;

    /**
     * 平台标识符
     */
    private String identifier;

    /**
     * 平台描述
     */
    private String description;

    /**
     * 图标
     */
    private String icon;

    /**
     * 官网链接
     */
    private String link;

    /**
     * 是否默认平台
     */
    private Boolean isDefault;

    /**
     * 配置Schema（JSON字符串）
     */
    private String configSchema;
}
