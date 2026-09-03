package com.guanghe.fs.storage.plugin.core.config;

import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.utils.StorageUtils;
import lombok.Builder;
import lombok.Data;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 存储配置
 * 用于封装存储平台的配置信息
 *
 * @Author: guangheUlti
 * @Date: 2024/10/26
 */
@Data
@Builder
public class StorageConfig {

    /**
     * 配置ID（数据库主键，唯一标识一个配置实例）
     * - null：表示 Local 存储
     * - 非 null：表示用户自定义配置
     */
    private String configId;

    /**
     * 平台标识符（如：local、aliyun_oss、minio）
     */
    private String platformIdentifier;

    /**
     * 所属工作空间ID
     */
    private String workspaceId;

    /**
     * 配置属性（JSON映射）
     * 不同平台的配置属性不同，通过 Map 灵活存储
     */
    private Map<String, Object> properties;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 备注（可选）
     */
    private String remark;

    /**
     * 类型安全的属性获取
     *
     * @param key  属性键
     * @param type 目标类型
     * @param <T>  泛型类型
     * @return 属性值，不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getProperty(String key, Class<T> type) {
        if (properties == null) {
            return null;
        }

        Object value = properties.get(key);
        if (value == null) {
            return null;
        }

        // 类型匹配，直接返回
        if (type.isInstance(value)) {
            return (T) value;
        }

        // 基础类型转换
        try {
            if (type == String.class) {
                return (T) value.toString();
            }
            if (type == Integer.class && value instanceof Number) {
                return (T) Integer.valueOf(((Number) value).intValue());
            }
            if (type == Long.class && value instanceof Number) {
                return (T) Long.valueOf(((Number) value).longValue());
            }
            if (type == Double.class && value instanceof Number) {
                return (T) Double.valueOf(((Number) value).doubleValue());
            }
            if (type == Boolean.class) {
                if (value instanceof Boolean) {
                    return (T) value;
                }
                return (T) Boolean.valueOf(value.toString());
            }
        } catch (Exception e) {
            throw new StorageOperationException(
                    String.format("Property '%s' cannot be cast to %s: %s",
                            key, type.getSimpleName(), e.getMessage())
            );
        }

        throw new StorageOperationException(
                String.format("Property '%s' cannot be cast to %s (value type: %s)",
                        key, type.getSimpleName(), value.getClass().getSimpleName())
        );
    }

    /**
     * 获取必需属性（不存在则抛异常）
     *
     * @param key  属性键
     * @param type 目标类型
     * @param <T>  泛型类型
     * @return 属性值
     * @throws StorageOperationException 如果属性不存在
     */
    public <T> T getRequiredProperty(String key, Class<T> type) {
        T value = getProperty(key, type);
        if (value == null) {
            throw new StorageOperationException(
                    String.format("Required property '%s' is missing in config [%s]",
                            key, configId)
            );
        }
        return value;
    }

    /**
     * 获取属性（带默认值）
     *
     * @param key          属性键
     * @param type         目标类型
     * @param defaultValue 默认值
     * @param <T>          泛型类型
     * @return 属性值，不存在返回默认值
     */
    public <T> T getProperty(String key, Class<T> type, T defaultValue) {
        T value = getProperty(key, type);
        return value != null ? value : defaultValue;
    }

    /**
     * 生成缓存键
     * Local 存储返回 "local:system"，用户配置返回 "{configId}:{platform}" 格式
     *
     * @return 缓存键
     */
    public String getCacheKey() {
        return StorageUtils.generateCacheKey(platformIdentifier, configId);
    }

    /**
     * 是否为 Local 存储
     * 委托给 {@link StorageUtils#isLocalConfig(String)}
     *
     * @return true-Local 存储
     */
    public boolean isLocal() {
        return StorageUtils.isLocalConfig(configId);
    }

    /**
     * 获取规范化的配置ID
     * 委托给 {@link StorageUtils#normalizeConfigId(String)}
     *
     * @return 规范化后的配置ID（Local 返回 null）
     */
    public String getNormalizedConfigId() {
        return StorageUtils.normalizeConfigId(configId);
    }

    /**
     * 验证配置有效性
     *
     * @throws StorageOperationException 如果配置无效
     */
    public void validate() {
        if (platformIdentifier == null || platformIdentifier.isBlank()) {
            throw new StorageOperationException("平台标识符不能为空");
        }

        if (!platformIdentifier.equals(StorageUtils.LOCAL_PLATFORM_IDENTIFIER) && (workspaceId == null || workspaceId.isBlank())) {
            throw new StorageOperationException("工作空间ID不能为空");
        }

        if (properties == null || properties.isEmpty()) {
            throw new StorageOperationException(
                    String.format("配置属性不能为空: configId=%s, platform=%s",
                            configId, platformIdentifier)
            );
        }
    }

    /**
     * 获取配置摘要信息（用于日志）
     *
     * @return 配置摘要字符串
     */
    public String getSummary() {
        return String.format("[configId=%s, platform=%s, workspaceId=%s, isLocal=%s, enabled=%s]",
                configId,
                platformIdentifier,
                workspaceId,
                isLocal(),
                enabled);
    }

    /**
     * 检查属性是否存在
     *
     * @param key 属性键
     * @return true-存在
     */
    public boolean hasProperty(String key) {
        return properties != null && properties.containsKey(key);
    }

    /**
     * 获取所有属性键
     *
     * @return 属性键集合
     */
    public java.util.Set<String> getPropertyKeys() {
        return properties != null
                ? java.util.Collections.unmodifiableSet(properties.keySet())
                : java.util.Collections.emptySet();
    }

    /**
     * 使用 Jackson 将 properties 转换为指定对象
     * 支持嵌套对象、集合等复杂类型
     *
     * @param targetClass 目标类型
     * @param <T>         泛型类型
     * @return 转换后的对象实例
     * @throws StorageOperationException 如果转换失败
     */
    public <T> T toObject(Class<T> targetClass) {
        if (properties == null || properties.isEmpty()) {
            throw new StorageOperationException("Properties is empty, cannot convert to object");
        }
        try {
            ObjectMapper mapper = JsonMapper.builder()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .build();
            return mapper.convertValue(properties, targetClass);
        } catch (Exception e) {
            throw new StorageOperationException(
                    String.format("Failed to convert properties to %s: %s",
                            targetClass.getSimpleName(), e.getMessage()), e);
        }
    }
}
