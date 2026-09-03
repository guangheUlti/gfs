package com.guanghe.fs.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.framework.common.constant.CommonConstant;
import com.guanghe.fs.storage.plugin.core.context.StoragePlatformContext;
import com.guanghe.fs.storage.plugin.core.context.StoragePlatformContextHolder;
import com.guanghe.fs.storage.plugin.core.utils.StorageUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 存储平台拦截器
 * 从请求头中提取存储平台标识并设置到上下文中
 *
 * @Author: guangheUlti
 * @Date: 2024/10/26
 */
@Slf4j
@Component
public class StoragePlatformInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        // 从请求头获取 configId
        String configId = request.getHeader(CommonConstant.X_STORAGE_PLATFORM_CONFIG_ID);

        // 规范化 configId（Local 统一转为 null）
        configId = StorageUtils.normalizeConfigId(configId);

        // 日志记录
        if (configId == null) {
            log.debug("使用 Local 存储");
        } else {
            log.debug("使用用户配置存储: configId={}", configId);
        }

        // 构建上下文
        StoragePlatformContext context = StoragePlatformContext.builder()
                .configId(configId)
                .userId(StpUtil.getLoginIdAsString())
                .build();

        // 设置到 ThreadLocal
        StoragePlatformContextHolder.setContext(context);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        // 清理 ThreadLocal，防止内存泄漏
        StoragePlatformContextHolder.clear();
    }
}
