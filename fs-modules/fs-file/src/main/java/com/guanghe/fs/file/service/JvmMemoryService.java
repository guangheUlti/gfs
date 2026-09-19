package com.guanghe.fs.file.service;

import com.guanghe.fs.file.domain.vo.JvmMemoryVO;

/**
 * JVM 最大内存配置读写与重启服务。
 * <p>
 * -Xmx 只能在 JVM 启动时生效，运行期调用仅将其写入启动脚本可读取的配置文件
 * （jvm-xmx.conf），并通过触发后端自身退出，由外层看门狗进程按新值重新拉起。
 *
 * @Author: guangheUlti
 */
public interface JvmMemoryService {

    /**
     * 读取当前配置值（MB）与运行实例实际最大堆内存。
     */
    JvmMemoryVO getConfig();

    /**
     * 保存 JVM 最大内存配置（MB）。null 表示清除配置、改用 JVM 默认值。
     * 修改需后端重启后才会实际生效。
     *
     * @param maxMemoryMb 非空时必须在 [256, 65536] 范围内
     */
    void saveConfig(Integer maxMemoryMb);

    /**
     * 触发后端优雅重启：写入重启标记后退出当前进程，由看门狗按最新配置重新拉起。
     */
    void triggerRestart();
}