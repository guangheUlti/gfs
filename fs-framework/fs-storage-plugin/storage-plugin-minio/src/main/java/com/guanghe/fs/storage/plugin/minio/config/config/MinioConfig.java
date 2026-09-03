package com.guanghe.fs.storage.plugin.minio.config.config;

import com.guanghe.fs.storage.plugin.core.s3.S3CompatibleConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import software.amazon.awssdk.regions.Region;

/**
 * Minio配置
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MinioConfig extends S3CompatibleConfig {

    public MinioConfig() {
        super();
        setPathStyleAccess(true);
        setRegion(Region.US_EAST_1);
    }
}
