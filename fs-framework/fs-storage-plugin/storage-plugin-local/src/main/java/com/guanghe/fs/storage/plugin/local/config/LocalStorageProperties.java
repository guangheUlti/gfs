package com.guanghe.fs.storage.plugin.local.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 本地存储默认配置（数据库是唯一生效来源，此处仅作为全新数据库首次初始化时的兜底默认值）
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Data
@Component
@ConfigurationProperties(prefix = "fs.storage.local")
public class LocalStorageProperties {

    /**
     * 存储基础路径（支持绝对路径与相对路径，相对路径按进程启动目录解析）
     */
    private String basePath = "./storage";

    /**
     * 是否开启落盘加密（默认关闭；开启时 encryptionSecret 必填）
     */
    private boolean encryptionEnabled = false;

    /**
     * 落盘加密口令（encryptionEnabled=true 时必填；变更后旧文件无法解密）
     */
    private String encryptionSecret;

    /**
     * 转换为 StorageConfig 的 properties Map
     */
    public Map<String, Object> toPropertiesMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("basePath", basePath);
        map.put("encryptionEnabled", encryptionEnabled);
        if (encryptionSecret != null) {
            map.put("encryptionSecret", encryptionSecret);
        }
        return map;
    }
}
