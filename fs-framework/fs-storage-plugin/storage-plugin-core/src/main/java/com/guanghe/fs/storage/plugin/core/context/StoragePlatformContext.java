package com.guanghe.fs.storage.plugin.core.context;

import com.guanghe.fs.storage.plugin.core.utils.StorageUtils;
import lombok.Builder;
import lombok.Data;

/**
 * 存储平台上下文
 * 用于在请求中传递存储相关信息
 *
 * @Author: guangheUlti
 * @Date: 2024/10/26
 */
@Data
@Builder
public class StoragePlatformContext {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 配置ID（支持多配置场景）
     * - null 或 "local"：表示使用 Local 存储
     * - 其他值：表示用户自定义配置ID
     */
    private String configId;

    public String getCacheKey() {
        return StorageUtils.isLocalConfig(configId) ? "local:system" : null;
    }

    /**
     * 是否为 Local 存储
     *
     * @return true-Local 存储
     */
    public boolean isLocal() {
        return StorageUtils.isLocalConfig(configId);
    }

    /**
     * 获取规范化的配置ID
     *
     * @return 规范化后的配置ID（Local 返回 null）
     */
    public String getNormalizedConfigId() {
        return StorageUtils.normalizeConfigId(configId);
    }
}
