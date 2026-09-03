package com.guanghe.fs.storage.plugin.core.dto;

import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 存储插件元数据
 * 从 @StoragePlugin 注解提取
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Data
@Builder
@Slf4j
public class StoragePluginMetadata {
    
    /** 平台标识符 */
    private String identifier;
    
    /** 平台名称 */
    private String name;
    
    /** 配置Schema (JSON) */
    private String configSchema;
    
    /** 图标标识 */
    private String icon;
    
    /** 官方链接 */
    private String link;
    
    /** 平台描述 */
    private String description;
    
    /** 是否默认平台 */
    private Boolean isDefault;
    
    /**
     * 从插件类提取元数据
     *
     * @param pluginClass 插件类
     * @return 插件元数据
     * @throws IllegalArgumentException 如果插件类未标注 @StoragePlugin 注解
     */
    public static StoragePluginMetadata fromPluginClass(Class<?> pluginClass) {
        StoragePlugin annotation = pluginClass.getAnnotation(StoragePlugin.class);
        
        if (annotation == null) {
            throw new IllegalArgumentException(
                "Plugin class must be annotated with @StoragePlugin: " + pluginClass.getName()
            );
        }
        
        // 获取配置Schema
        String schema = resolveConfigSchema(annotation);
        
        return StoragePluginMetadata.builder()
            .identifier(annotation.identifier())
            .name(annotation.name())
            .description(annotation.description().isEmpty() ? annotation.name() : annotation.description())
            .icon(annotation.icon())
            .link(annotation.link().isEmpty() ? null : annotation.link())
            .isDefault(annotation.isDefault())
            .configSchema(schema)
            .build();
    }
    
    /**
     * 解析配置Schema
     * 优先使用 configSchema，其次使用 schemaResource
     *
     * @param annotation StoragePlugin 注解
     * @return 配置Schema JSON字符串
     */
    private static String resolveConfigSchema(StoragePlugin annotation) {
        // 优先使用直接定义的 schema
        if (!annotation.configSchema().isEmpty()) {
            return annotation.configSchema();
        }
        
        // 其次从资源文件加载
        if (!annotation.schemaResource().isEmpty()) {
            try {
                return loadSchemaFromResource(annotation.schemaResource());
            } catch (Exception e) {
                log.warn("Failed to load schema from resource: {}", annotation.schemaResource(), e);
            }
        }
        
        return "{}";
    }
    
    /**
     * 从资源文件加载Schema
     *
     * @param resourcePath 资源文件路径
     * @return Schema JSON字符串
     */
    private static String loadSchemaFromResource(String resourcePath) {
        // 移除 classpath: 前缀
        String path = resourcePath.startsWith("classpath:") 
            ? resourcePath.substring(10) 
            : resourcePath;
        
        try (InputStream is = StoragePluginMetadata.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                log.warn("Schema resource file not found: {}", resourcePath);
                return "{}";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to load schema resource file: {}, error: {}", resourcePath, e.getMessage());
            return "{}";
        }
    }
}
