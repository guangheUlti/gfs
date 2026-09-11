package com.guanghe.fs.service.web;

import cn.dev33.satoken.strategy.hooks.SaFirewallCheckHookForHttpMethod;
import com.guanghe.fs.service.webdav.DavAuthFilter;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * WebDAV 过滤器注册：只拦 /dav/*。
 * <ul>
 *   <li>order 必须晚于 sa-token 的上下文过滤器（SaTokenContextFilterForJakartaServlet，
 *       @Order(-104)），否则过滤器阶段 StpUtil 会抛 "SaTokenContext 上下文尚未初始化"；</li>
 *   <li>早于 SaFirewallCheckFilter（-103）之外的常规过滤器即可，这里取 -100；</li>
 *   <li>/dav/** 已在 security.excludes 中排除登录校验，由本 filter 完成 Basic 认证。</li>
 * </ul>
 */
@Configuration
public class WebDavFilterConfig {

    /**
     * sa-token 防火墙默认只放行 GET/POST/PUT/DELETE 等常规方法，
     * WebDAV 方法（PROPFIND/MKCOL 等）会被 "非法请求 Method" 拦截，启动时追加白名单。
     */
    @PostConstruct
    public void allowDavMethodsInFirewall() {
        List<String> allow = SaFirewallCheckHookForHttpMethod.instance.allowMethods;
        for (String m : List.of("PROPFIND", "PROPPATCH", "MKCOL", "MOVE", "COPY", "LOCK", "UNLOCK")) {
            if (!allow.contains(m)) {
                allow.add(m);
            }
        }
    }

    @Bean
    public FilterRegistrationBean<DavAuthFilter> davAuthFilterRegistration(DavAuthFilter davAuthFilter) {
        FilterRegistrationBean<DavAuthFilter> registration = new FilterRegistrationBean<>(davAuthFilter);
        registration.addUrlPatterns("/dav/*");
        registration.setOrder(-100);
        registration.setName("davAuthFilter");
        return registration;
    }
}
