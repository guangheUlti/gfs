package com.guanghe.fs.service.spi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外文件服务注册表：收集 Spring 容器内全部 {@link ExternalFileService} 实现，
 * 按 type 建立只读注册表。新增协议实现只需标注 @Component，无需改动管理器/控制器。
 */
@Slf4j
@Component
public class ExternalFileServiceRegistry {

    private final Map<String, ExternalFileService> services = new LinkedHashMap<>();

    public ExternalFileServiceRegistry(List<ExternalFileService> implementations) {
        for (ExternalFileService impl : implementations) {
            ExternalFileService existing = services.put(impl.type(), impl);
            if (existing != null) {
                log.warn("对外文件服务类型重复注册，后者覆盖前者: type={}, old={}, new={}",
                        impl.type(), existing.getClass().getSimpleName(), impl.getClass().getSimpleName());
            }
        }
        log.info("对外文件服务注册完成: {}", services.keySet());
    }

    /**
     * 按类型取实现
     *
     * @param type 服务类型（webdav / sftp / ftp）
     * @return 实现；未注册返回 null
     */
    public ExternalFileService get(String type) {
        return services.get(type);
    }

    /** 是否存在该类型的实现 */
    public boolean has(String type) {
        return services.containsKey(type);
    }

    /** 全部已注册类型（按注册顺序） */
    public Map<String, ExternalFileService> all() {
        return java.util.Collections.unmodifiableMap(services);
    }
}
