package com.guanghe.fs.storage.plugin.core.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StoragePlatformContextHolder {

    private static final TransmittableThreadLocal<StoragePlatformContext> CONTEXT_HOLDER =
            new TransmittableThreadLocal<>();

    /**
     * 设置上下文
     */
    public static void setContext(StoragePlatformContext context) {
        if (context == null) {
            throw new IllegalArgumentException("存储平台上下文不能为空");
        }
        log.debug("设置存储平台上下文: userId={}, configId={}, isLocal={}",
                context.getUserId(), context.getConfigId(), context.isLocal());
        CONTEXT_HOLDER.set(context);
    }

    /**
     * 获取上下文（推荐使用）
     *
     * @return 存储平台上下文，如果未设置返回 null
     */
    public static StoragePlatformContext getContext() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 获取上下文（必须存在，否则抛异常）
     * 用于必须要求登录的场景
     */
    public static StoragePlatformContext getRequiredContext() {
        StoragePlatformContext context = CONTEXT_HOLDER.get();
        if (context == null) {
            throw new StorageOperationException(
                    "存储平台上下文未设置，请检查拦截器配置或确保在 HTTP 请求中访问"
            );
        }
        return context;
    }

    /**
     * 获取用户ID
     */
    public static String getUserId() {
        StoragePlatformContext context = getContext();
        return context != null ? context.getUserId() : null;
    }

    /**
     * 获取配置ID
     */
    public static String getConfigId() {
        StoragePlatformContext context = getContext();
        return context != null ? context.getConfigId() : null;
    }

    /**
     * 获取规范化的配置ID
     */
    public static String getNormalizedConfigId() {
        StoragePlatformContext context = getContext();
        return context != null ? context.getNormalizedConfigId() : null;
    }

    /**
     * 判断当前是否为 Local 存储
     */
    public static boolean isLocal() {
        StoragePlatformContext context = getContext();
        return context != null && context.isLocal();
    }

    /**
     * 清除上下文
     */
    public static void clear() {
        StoragePlatformContext context = CONTEXT_HOLDER.get();
        if (context != null) {
            log.debug("清除存储平台上下文: userId={}, configId={}",
                    context.getUserId(), context.getConfigId());
        }
        CONTEXT_HOLDER.remove();
    }

    /**
     * 检查上下文是否存在
     */
    public static boolean hasContext() {
        return CONTEXT_HOLDER.get() != null;
    }

    // ... 其他方法保持不变
}
