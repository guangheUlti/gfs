package com.guanghe.fs.storage.plugin.core;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Objects;

/**
 * 抽象存储操作服务
 * 提供公共的方法实现和初始化状态管理
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
public abstract class AbstractStorageOperationService implements IStorageOperationService {

    /**
     * 配置（不可变）
     */
    protected final StorageConfig config;

    /**
     * 配置化构造函数
     */
    protected AbstractStorageOperationService(StorageConfig config) {
        this.config = Objects.requireNonNull(config, "StorageConfig cannot be null");
        validateConfig(config);
        initialize(config);
    }

    /**
     * 原型构造函数（SPI加载用）
     */
    protected AbstractStorageOperationService() {
        this.config = null;
    }

    /**
     * 验证配置（子类实现）
     * 职责：验证插件特有字段（如 endpoint、bucket、accessKey）
     */
    protected abstract void validateConfig(StorageConfig config);

    /**
     * 初始化资源（子类实现）
     */
    protected abstract void initialize(StorageConfig config);

    /**
     * 工厂方法默认实现
     * 从注解获取标识符用于日志
     */
    @Override
    public IStorageOperationService createConfiguredInstance(StorageConfig config) {
        try {
            Constructor<? extends IStorageOperationService> constructor =
                    this.getClass().getConstructor(StorageConfig.class);
            return constructor.newInstance(config);
        } catch (Exception e) {
            // 从注解获取标识符
            String identifier = getIdentifierFromAnnotation();
            throw new StorageOperationException(
                    "Failed to create instance for platform: " + identifier, e
            );
        }
    }

    /**
     * 从注解获取平台标识符
     *
     * @return 平台标识符，如果未标注注解则返回 "unknown"
     */
    protected String getIdentifierFromAnnotation() {
        StoragePlugin annotation = this.getClass().getAnnotation(StoragePlugin.class);
        return annotation != null ? annotation.identifier() : "unknown";
    }

    /**
     * 检查是否为原型实例
     */
    protected boolean isPrototype() {
        return config == null;
    }

    /**
     * 确保不是原型实例
     */
    protected void ensureNotPrototype() {
        if (isPrototype()) {
            throw new StorageOperationException(
                    "Cannot invoke business methods on prototype instance"
            );
        }
    }

    /**
     * MultipartFile上传的默认实现
     */
    public void uploadFile(MultipartFile file, String objectKeyPrefix) {
        ensureNotPrototype();

        if (file == null || file.isEmpty()) {
            throw new StorageOperationException("Upload file cannot be empty");
        }

        try {
            String originalFileName = file.getOriginalFilename();
            if (StrUtil.isBlank(originalFileName)) {
                originalFileName = "unknown-" + IdUtil.fastSimpleUUID();
            }

            // 构建对象键
            String prefix = StrUtil.isBlank(objectKeyPrefix) ? "" :
                    (objectKeyPrefix.endsWith("/") ? objectKeyPrefix : objectKeyPrefix + "/");

            String safeFileName = originalFileName.replaceAll("\\.\\./", "")
                    .replaceAll("\\.\\\\", "");
            String objectKey = prefix + IdUtil.fastSimpleUUID() + "-" + safeFileName;

            uploadFile(file.getInputStream(), objectKey);
        } catch (IOException e) {
            throw new StorageOperationException("Upload file failed: " + e.getMessage(), e);
        }
    }

    /**
     * 获取日志前缀
     */
    protected String getLogPrefix() {
        if (config == null) {
            return "[Prototype]";
        }
        return String.format("[%s|%s]", config.getWorkspaceId(), config.getPlatformIdentifier());
    }
}