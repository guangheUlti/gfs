package com.guanghe.fs.storage.plugin.kodo.config;

import lombok.Data;

/**
 * 七牛云 Kodo 存储插件配置
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Data
public class KodoConfig {

    /**
     * AC
     */
    private String accessKey;
    /**
     * SC
     */
    private String secretKey;
    /**
     * 存储空间
     */
    private String bucket;
    /**
     * 访问域名
     */
    private String domain;
}
