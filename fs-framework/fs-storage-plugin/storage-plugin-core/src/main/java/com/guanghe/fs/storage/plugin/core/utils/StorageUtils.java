package com.guanghe.fs.storage.plugin.core.utils;

import cn.hutool.core.util.StrUtil;

import java.util.List;

/**
 * 存储工具类
 *
 * @Author: guangheUlti
 * @Date: 2024/10/26
 */
public class StorageUtils {

    /**
     * 本地存储平台标识符常量
     */
    public static final String LOCAL_PLATFORM_IDENTIFIER = "Local";

    /**
     * 存储平台展示顺序（平台下拉、存储切换、配置/服务页列表统一按此排序；未列出的平台排最后）
     */
    private static final List<String> PLATFORM_DISPLAY_ORDER = List.of(
            LOCAL_PLATFORM_IDENTIFIER,
            "LocalMount",
            "LocalDirect",
            "Memory",
            "WebDAV",
            "SFTP",
            "FTP",
            "Smb",
            "RustFS",
            "AliyunOSS",
            "Minio"
    );

    /**
     * 获取平台展示顺序权重，越小越靠前；未收录的平台返回 Integer.MAX_VALUE
     */
    public static int platformDisplayOrder(String identifier) {
        int index = identifier == null ? -1 : PLATFORM_DISPLAY_ORDER.indexOf(identifier);
        return index >= 0 ? index : Integer.MAX_VALUE;
    }

    /**
     * 判断是否为 Local 存储配置
     *
     * @param configId 配置ID
     * @return true-是 Local 存储
     */
    public static boolean isLocalConfig(String configId) {
        return configId == null
                || LOCAL_PLATFORM_IDENTIFIER.equals(configId);
    }

    /**
     * 规范化配置ID（Local 统一转为 null）
     *
     * @param configId 原始配置ID
     * @return 规范化后的配置ID
     */
    public static String normalizeConfigId(String configId) {
        return isLocalConfig(configId) ? null : configId;
    }

    /**
     * 规范化路径（去除末尾分隔符）
     *
     * @param path      路径
     * @param separator 分隔符
     * @return 规范化后的路径
     */
    public static String normalizePath(String path, String separator) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        String trimmed = path.trim();
        return trimmed.endsWith(separator)
                ? trimmed.substring(0, trimmed.length() - separator.length())
                : trimmed;
    }

    public static String generateCacheKey(String platformIdentifier, String configId) {
        if (isLocalConfig(configId)) {
            return "local:system";
        }
        if (platformIdentifier == null || platformIdentifier.isBlank()) {
            throw new IllegalArgumentException("平台标识符不能为空: configId=" + configId);
        }
        // 格式：configId:platformIdentifier（configId 在前，方便提取）
        return configId + ":" + platformIdentifier;
    }
}
