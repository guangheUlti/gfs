package com.guanghe.fs.framework.security;

import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import com.guanghe.fs.framework.security.properties.SecurityProperties;
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

    @Bean
    public StpLogic getStpLogicJwt() {
        return new StpLogicJwtForSimple();
    }
}
