package com.guanghe.fs.system.web;

import com.guanghe.fs.system.service.IpGuardService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IP 防护过滤器注册。
 * <p>
 * URL 模式覆盖「网盘 Web 接口 + 四个对外协议入口」，不写 {@code /*} 全量拦截：
 * 前端静态资源与 {@code /actuator} 必须始终可达，否则被限 IP 的人连登录页都打不开，
 * 只剩一个白屏看不出原因；健康检查被挡还会让运维误判服务挂了。
 * <p>
 * order 取 -102：晚于 sa-token 上下文过滤器（-104）与防火墙（-103），
 * 早于 WebDAV / OSS / AList 的认证过滤器（-100），名单外的请求不会走到密码校验。
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
@Configuration
public class IpGuardFilterConfig {

    @Bean
    public FilterRegistrationBean<IpGuardFilter> ipGuardFilterRegistration(IpGuardService ipGuardService) {
        FilterRegistrationBean<IpGuardFilter> registration =
                new FilterRegistrationBean<>(new IpGuardFilter(ipGuardService));
        registration.addUrlPatterns(
                "/apis/*",
                "/api/*",
                "/dav/*",
                "/oss/*",
                "/d/*",
                "/preview/*",
                "/archive/*");
        registration.setOrder(-102);
        registration.setName("ipGuardFilter");
        return registration;
    }
}
