package com.guanghe.fs.framework.common.enums;

import lombok.Getter;

/**
 * 存储平台标识枚举,这里需要和数据库中的存储平台标识保持一致
 *
 * <p><b>已废弃:</b> 此枚举类已被废弃，将在未来版本中移除。</p>
 * 
 * <p><b>迁移方案:</b></p>
 * <ul>
 *   <li>存储插件的元数据（标识符、名称、图标等）现在通过 {@code @StoragePlugin} 注解声明</li>
 *   <li>获取插件标识符请使用 {@code StoragePluginRegistry.getIdentifier(plugin)} 方法</li>
 *   <li>获取插件元数据请使用 {@code StoragePluginRegistry.getMetadata(identifier)} 方法</li>
 *   <li>对于本地存储标识符，可直接使用字符串常量 "Local"</li>
 * </ul>
 * 
 * <p><b>示例:</b></p>
 * <pre>{@code
 * // 旧方式 (已废弃)
 * String identifier = StoragePlatformIdentifierEnum.LOCAL.getIdentifier();
 * 
 * // 新方式 - 使用字符串常量
 * String identifier = "Local";
 * 
 * // 新方式 - 从注解获取
 * StoragePluginMetadata metadata = pluginRegistry.getMetadata("Local");
 * String identifier = metadata.getIdentifier();
 * }</pre>
 *
 * @Author: guangheUlti
 * @Date: 2025/5/8 9:06
 * @deprecated 自 v2.0 起废弃，请使用 {@code @StoragePlugin} 注解声明插件元数据
 * @see com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin
 * @see com.guanghe.fs.storage.plugin.boot.StoragePluginRegistry
 */
@Getter
@Deprecated(since = "2.0", forRemoval = true)
public enum StoragePlatformIdentifierEnum {
    LOCAL("Local", "本地存储", "icon-bendicunchu1"),
    RUSTFS("RustFS", "RustFS对象存储", "icon-bendicunchu1"),
    ALIYUN_OSS("AliyunOSS", "阿里云OSS", "icon-aliyun1"),
    QINIU_KODO("Kodo", "七牛云Kodo", "icon-normal-logo-blue");

    private final String identifier;
    private final String description;
    private final String icon;

    StoragePlatformIdentifierEnum(String identifier, String description, String icon) {
        this.identifier = identifier;
        this.description = description;
        this.icon = icon;
    }

    /**
     * 根据标识符获取枚举值
     * 
     * @param identifier 平台标识符
     * @return 对应的枚举值
     * @throws IllegalArgumentException 如果标识符未知
     * @deprecated 请使用 {@code StoragePluginRegistry.getMetadata(identifier)} 替代
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public static StoragePlatformIdentifierEnum fromIdentifier(String identifier) {
        for (StoragePlatformIdentifierEnum type : values()) {
            if (type.getIdentifier().equalsIgnoreCase(identifier)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown storage platform identifier: " + identifier);
    }
}