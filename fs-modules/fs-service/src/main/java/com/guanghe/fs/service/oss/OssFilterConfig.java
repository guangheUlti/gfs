package com.guanghe.fs.service.oss;

import com.guanghe.fs.service.webdav.DavAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OSS 过滤器注册：只拦 /oss/*。
 * <ul>
 *   <li>order 必须晚于 sa-token 的上下文过滤器（SaTokenContextFilterForJakartaServlet，@Order(-104)），</li>
 *   <li>与 WebDAV 的 {@link DavAuthFilter}（-100）互不重叠（URL 模式不同），取同值 -100；</li>
 *   <li>/oss/** 已在 security.excludes 中排除登录校验，由本 filter 完成 SigV4 认证。</li>
 * </ul>
 */
@Configuration
public class OssFilterConfig {

    @Bean
    public FilterRegistrationBean<OssAuthFilter> ossAuthFilterRegistration(OssAuthFilter ossAuthFilter) {
        FilterRegistrationBean<OssAuthFilter> registration = new FilterRegistrationBean<>(ossAuthFilter);
        registration.addUrlPatterns("/oss/*");
        registration.setOrder(-100);
        registration.setName("ossAuthFilter");
        return registration;
    }
}
