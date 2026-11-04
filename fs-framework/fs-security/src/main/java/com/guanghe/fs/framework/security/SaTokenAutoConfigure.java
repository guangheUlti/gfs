package com.guanghe.fs.framework.security;

import com.guanghe.fs.framework.common.utils.IpUtils;
import com.guanghe.fs.framework.security.properties.SecurityProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * satoken配置
 *
 * @Author: guangheUlti
 * @Date: 2024/10/16 13:50
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SaTokenAutoConfigure {

    /**
     * 把反代开关推给静态的 IP 解析工具，让所有按 IP 记账的防护共用同一个来源口径。
     */
    @Bean
    public InitializingBean clientIpSourceInitializer(SecurityProperties properties) {
        return () -> IpUtils.setTrustProxyHeaders(properties.isTrustProxy());
    }
}
