package com.guanghe.fs.storage.plugin.core.annotation;

import java.lang.annotation.*;

/**
 * 存储插件注解
 * 用于标注存储插件实现类，声明插件元数据
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StoragePlugin {

    /**
     * 平台标识符（唯一）
     * 如：Local, AliyunOSS, RustFS
     *
     * @return 平台标识符
     */
    String identifier();

    /**
     * 平台显示名称
     * 如：本地存储, 阿里云OSS
     *
     * @return 平台显示名称
     */
    String name();

    /**
     * 平台描述
     *
     * @return 平台描述，默认为空字符串
     */
    String description() default "";

    /**
     * 平台图标标识
     *
     * @return 图标标识，默认为 "icon-storage"
     */
    String icon() default "icon-storage";

    /**
     * 平台官方链接
     *
     * @return 官方链接，默认为空字符串
     */
    String link() default "";

    /**
     * 是否为默认平台（仅首次注册时生效）
     *
     * @return 是否为默认平台，默认为 false
     */
    boolean isDefault() default false;

    /**
     * 配置Schema（JSON格式）
     * 可以直接写JSON字符串，或者使用 schemaResource 指定资源文件
     *
     * @return 配置Schema JSON字符串，默认为空字符串
     */
    String configSchema() default "";

    /**
     * 配置Schema资源文件路径
     * 如：classpath:schema/local-storage-schema.json
     * 当 configSchema 为空时使用此配置
     *
     * @return 资源文件路径，默认为空字符串
     */
    String schemaResource() default "";
}
