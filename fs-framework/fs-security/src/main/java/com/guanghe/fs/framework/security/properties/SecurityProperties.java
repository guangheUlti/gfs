package com.guanghe.fs.framework.security.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security 配置属性
 */
@Data
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    private String pathPattern;

    /**
     * 排除路径
     */
    private String[] excludes;

    /**
     * 应用是否部署在可信反向代理之后。
     * <p>
     * false（默认）：客户端 IP 一律取 TCP 层对端地址，X-Forwarded-For 等可自造头全部忽略。
     * true：允许从转发头解析真实来源 IP，供 Nginx 等反代部署使用，需代理自己写对转发头。
     */
    private boolean trustProxy = false;
}
