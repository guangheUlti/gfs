package com.guanghe.fs.file.mount;

import com.guanghe.fs.framework.common.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 挂载组件统一持有入口
 * 以单例 Bean 的方式暴露 MountLocks，供 fs-file 各服务与扫描器共享同一把锁表。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@Component
public class MountManager {

    private final MountLocks mountLocks = new MountLocks();

    public MountLocks locks() {
        return mountLocks;
    }

    /**
     * 判断显示名是否可安全用作挂载路径段（含 / 或 \ 的名字会破坏路径还原）
     */
    public static boolean isValidNameSegment(String name) {
        if (StringUtils.isEmpty(name)) {
            return false;
        }
        return !name.contains("/") && !name.contains("\\");
    }
}
