package com.guanghe.fs.storage.plugin.rustfs.config;

import com.guanghe.fs.storage.plugin.core.s3.S3CompatibleConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * OSS（S3 兼容）配置
 * <p>
 * 默认路径样式访问（MinIO / RustFS 约定）；接入阿里云 OSS、AWS S3 等虚拟主机风格服务时，
 * 表单需选择「虚拟主机」并填写对应区域。区域留空时回退 us-east-1。
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RustFsConfig extends S3CompatibleConfig {

    public RustFsConfig() {
        super();
        setPathStyleAccess(true);
    }
}
