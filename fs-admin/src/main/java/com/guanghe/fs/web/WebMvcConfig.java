package com.guanghe.fs.web;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.framework.security.properties.SecurityProperties;
import com.guanghe.fs.interceptor.PreviewInterceptor;
import com.guanghe.fs.interceptor.StoragePlatformInterceptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Web 配置
 *
 * @Author: guangheUlti
 * @Date: 2024/11/18 13:53
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private StoragePlatformInterceptor storagePlatformInterceptor;

    @Autowired
    private PreviewInterceptor previewInterceptor;

    @Autowired
    private org.springframework.boot.autoconfigure.web.WebProperties webProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 前端 SPA 静态资源与 history 路由回退：未命中的非接口路径回退到 index.html。
        // 位置来源：spring.web.resources.static-locations（部署包用它指向外部
        // frontend/ 目录，实现换前端产物无需重打 JAR）；未配置时回退 classpath:/static/。
        // 注意：在这里显式注册 handler 会取代 Spring Boot 的默认静态映射，
        // 因此必须自行透传 yml 的 static-locations 配置。
        List<String> locations = new ArrayList<>();
        for (String l : webProperties.getResources().getStaticLocations()) {
            locations.add(l.endsWith("/") ? l : l + "/");
        }
        if (locations.isEmpty()) {
            locations.add("classpath:/static/");
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(locations.toArray(String[]::new))
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 接口路径不回退到前端页面，保持 404 行为
                        if (resourcePath.startsWith("apis/") || resourcePath.startsWith("api/")
                                || resourcePath.startsWith("preview/") || resourcePath.startsWith("archive/")) {
                            return null;
                        }
                        Resource index = location.createRelative("index.html");
                        return index.exists() && index.isReadable() ? index : null;
                    }
                });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String[] previewTokenPaths = {"/preview/token/**", "/archive/preview/token/**"};

        // Sa-Token 登录校验拦截器
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns(securityProperties.getPathPattern())
                .excludePathPatterns(securityProperties.getExcludes())
                .order(1);

        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns(previewTokenPaths)
                .order(1);


        //注册存储平台切换拦截器
        registry.addInterceptor(storagePlatformInterceptor)
                .addPathPatterns(securityProperties.getPathPattern())
                .excludePathPatterns(securityProperties.getExcludes())
                .order(2);

        registry.addInterceptor(storagePlatformInterceptor)
                .addPathPatterns(previewTokenPaths)
                .order(2);

        //注册文件预览防盗链拦截器
        registry.addInterceptor(previewInterceptor)
                .addPathPatterns("/preview/**", "/archive/preview/**", "/api/file/stream/preview/**")
                .excludePathPatterns("/preview/token/**", "/preview/error", "/archive/preview/token/**")
                .order(3);
    }
}
