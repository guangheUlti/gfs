package com.guanghe.fs.framework.common.context;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 工作空间上下文 - 用于在请求处理过程中传递工作空间ID
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
public class WorkspaceContext {

    private static final TransmittableThreadLocal<String> CURRENT = new TransmittableThreadLocal<>();

    /**
     * 设置当前工作空间ID
     */
    public static void setWorkspaceId(String workspaceId) {
        CURRENT.set(workspaceId);
    }

    /**
     * 获取当前工作空间ID
     */
    public static String getWorkspaceId() {
        return CURRENT.get();
    }

    /**
     * 清除当前工作空间ID
     */
    public static void clear() {
        CURRENT.remove();
    }
}
