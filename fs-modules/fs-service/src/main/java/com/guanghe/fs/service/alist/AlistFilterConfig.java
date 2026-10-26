package com.guanghe.fs.service.alist;

import com.guanghe.fs.service.webdav.DavAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AList 过滤器注册。
 * <p>
 * URL 模式刻意只覆盖本协议自己的入口（{@code /api/auth/*}、{@code /api/public/*}、
 * {@code /api/me}、{@code /api/fs/*}、{@code /d/*}），不写成 {@code /api/*}：
 * {@code /api/file/stream/**} 是网盘 Web 端自用的预览/播放流接口，一旦被本过滤器拦下，
 * 前端播放器会全部 401。
 * <p>
 * order 与 {@link DavAuthFilter}（-100）一致：晚于 sa-token 上下文过滤器（-104），
 * 否则过滤器阶段 {@code StpUtil} 会抛「上下文尚未初始化」。
 */
@Configuration
public class AlistFilterConfig {

    @Bean
    public FilterRegistrationBean<AlistAuthFilter> alistAuthFilterRegistration(AlistAuthFilter filter) {
        FilterRegistrationBean<AlistAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/auth/*", "/api/public/*", "/api/me", "/api/fs/*", "/d/*");
        registration.setOrder(-100);
        registration.setName("alistAuthFilter");
        return registration;
    }
}
