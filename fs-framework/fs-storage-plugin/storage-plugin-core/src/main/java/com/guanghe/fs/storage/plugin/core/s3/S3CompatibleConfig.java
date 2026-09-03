package com.guanghe.fs.storage.plugin.core.s3;

import lombok.Data;
import software.amazon.awssdk.regions.Region;

/**
 * S3兼容存储配置基类
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Data
public abstract class S3CompatibleConfig {

    /**
     * Endpoint（必填）
     */
    private String endpoint;

    /**
     * AccessKey（必填）
     */
    private String accessKey;

    /**
     * SecretKey（必填）
     */
    private String secretKey;

    /**
     * Bucket（必填）
     */
    private String bucket;

    /**
     * Region（可选，默认us-east-1）
     */
    private Region region;

    /**
     * 是否使用路径样式访问（可选，默认false）
     * MinIO需要设置为true
     */
    private Boolean pathStyleAccess = false;

    /**
     * 自定义域名（可选）
     * 用于生成文件URL
     */
    private String customDomain;

    /**
     * 连接超时时间（毫秒，默认10秒）
     */
    private Integer connectionTimeout = 10000;

    /**
     * 读取超时时间（毫秒，默认30秒）
     */
    private Integer socketTimeout = 30000;
}
