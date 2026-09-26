package com.guanghe.fs.storage.plugin.rustfs;

import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.s3.AbstractS3CompatibleStorageService;
import com.guanghe.fs.storage.plugin.rustfs.config.RustFsConfig;

/**
 * RustFS 存储插件实现
 * 基于S3兼容协议的对象存储服务
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@StoragePlugin(
        identifier = "RustFS",
        name = "RustFS对象",
        description = "RustFS 是一个基于 Rust 构建的高性能分布式对象存储系统。Rust 是全球最受开发者喜爱的编程语言之一，RustFS 完美结合了 MinIO 的简洁性与 Rust 的内存安全及高性能优势。它提供完整的 S3 兼容性，完全开源，并专为数据湖、人工智能（AI）和大数据负载进行了优化。",
        icon = "icon-bendicunchu1",
        link = "https://github.com/rustfs/rustfs",
        schemaResource = "classpath:schema/rustfs-schema.json"
)
public class RustFsStorageServiceImpl extends AbstractS3CompatibleStorageService<RustFsConfig> {

    public RustFsStorageServiceImpl() {
        super();
    }

    public RustFsStorageServiceImpl(StorageConfig config) {
        super(config);
    }

    @Override
    protected Class<RustFsConfig> getS3ConfigClass() {
        return RustFsConfig.class;
    }
}
