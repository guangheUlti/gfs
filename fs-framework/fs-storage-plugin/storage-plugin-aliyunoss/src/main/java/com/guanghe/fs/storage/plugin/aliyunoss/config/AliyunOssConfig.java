package com.guanghe.fs.storage.plugin.aliyunoss.config;

import lombok.Data;

/**
 * 阿里云 OSS 存储插件配置
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Data
public class AliyunOssConfig {

    private String endpoint;

    private String accessKey;

    private String secretKey;

    private String bucket;

    private String region;
}
