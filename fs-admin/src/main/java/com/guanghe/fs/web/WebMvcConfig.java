package com.guanghe.fs.web;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.framework.security.properties.SecurityProperties;
import com.guanghe.fs.interceptor.PreviewInterceptor;
import com.guanghe.fs.interceptor.StoragePlatformInterceptor;
import com.guanghe.fs.interceptor.WorkspaceInterceptor;
import com.guanghe.fs.storage.plugin.local.config.LocalStorageProperties;
import java.io.IOException;
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
    private WorkspaceInterceptor workspaceInterceptor;

    @Autowired
    private LocalStorageProperties storageProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String baseUrl = storageProperties.getBaseUrl();
        // 提取最后一个 / 之后的内容
        String prefix = baseUrl.substring(baseUrl.lastIndexOf("/") + 1);

        registry.addResourceHandler("/" + prefix + "/**")
                .addResourceLocations("file:" + storageProperties.getBasePath() + "/");

        // 前端 SPA 静态资源与 history 路由回退：未命中的非接口路径回退到 index.html
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
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

        // 工作空间上下文拦截器（在登录校验之后）
        registry.addInterceptor(workspaceInterceptor)
                .addPathPatterns("/apis/**")
                .addPathPatterns(previewTokenPaths)
                .order(1);

        // Sa-Token 登录校验拦截器
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns(securityProperties.getPathPattern())
                .excludePathPatterns(securityProperties.getExcludes())
                .order(2);

        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns(previewTokenPaths)
                .order(2);


        //注册存储平台切换拦截器
        registry.addInterceptor(storagePlatformInterceptor)
                .addPathPatterns(securityProperties.getPathPattern())
                .excludePathPatterns(securityProperties.getExcludes())
                .order(3);

        registry.addInterceptor(storagePlatformInterceptor)
                .addPathPatterns(previewTokenPaths)
                .order(3);

        //注册文件预览防盗链拦截器
        registry.addInterceptor(previewInterceptor)
                .addPathPatterns("/preview/**", "/archive/preview/**", "/api/file/stream/preview/archive/inner/**")
                .excludePathPatterns("/preview/token/**", "/preview/error", "/archive/preview/token/**")
                .order(4);
    }
}
