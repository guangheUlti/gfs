package com.guanghe.fs.storage.plugin.rustfs.config;

import com.guanghe.fs.storage.plugin.core.s3.S3CompatibleConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import software.amazon.awssdk.regions.Region;

/**
 * RustFS配置
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
        setRegion(Region.US_EAST_1);
    }
}
