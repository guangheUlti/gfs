package com.guanghe.fs.storage.plugin.memory.config;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.Data;

/**
 * 内存挂载配置
 *
 * @Author: guangheUlti
 * @Date: 2026/09/26
 */
@Data
public class MemoryConfig {

    /** 挂载显示名（用户根目录下的挂载目录名） */
    private String mountName;

    /** 内存大小（MB，必填；前端以字符串提交，数字由 Jackson 宽松转换） */
    private String capacityMb;

    /** 从 StorageConfig 转换为配置对象 */
    public static MemoryConfig toObject(StorageConfig config) {
        return config.toObject(MemoryConfig.class);
    }

    /**
     * 解析内存大小（MB）：非法/非正数返回 -1（由 validateConfig 拒绝）。
     */
    public long parsedCapacityMb() {
        try {
            if (capacityMb == null || capacityMb.trim().isEmpty()) {
                return -1;
            }
            return Long.parseLong(capacityMb.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
