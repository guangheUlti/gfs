package com.guanghe.fs.storage.plugin.rustfs;

import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.s3.AbstractS3CompatibleStorageService;
import com.guanghe.fs.storage.plugin.rustfs.config.RustFsConfig;

/**
 * 通用 OSS 插件实现（S3 兼容协议）
 * <p>
 * 通过标准 S3 协议接入对象存储：阿里云 OSS、MinIO、RustFS、AWS S3 等。
 * 标识符保留 {@code RustFS}（历史标识），避免升级时把存量配置当作孤儿清理。
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@StoragePlugin(
        identifier = "RustFS",
        name = "OSS",
        description = "通过标准 S3 协议接入对象存储：阿里云 OSS、MinIO、RustFS、AWS S3 等。文件上传进桶并建立索引，支持秒传、去重、分享与直链；端点、区域与寻址方式按服务商要求填写。",
        icon = "icon-bendicunchu1",
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
