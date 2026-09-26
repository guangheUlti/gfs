package com.guanghe.fs.storage.plugin.localmount;

import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;

/**
 * 本地挂载（LocalDirect，直读）存储插件
 * <p>
 * 与 LocalMount 共用同一套本地文件读写实现（根路径归一化、防逃逸、写穿透、回收站物理删除），
 * 但不建立后台扫描索引：浏览某目录时由 fs-file 侧按层实时对账索引行（真实 FS 为唯一真相源），
 * 每次增删改查都直接作用于真实文件系统，外部改动刷新即可见。
 * <p>
 * 能力位：{@code isMountMode=true}（复用挂载写穿透链路）+ {@code isDirectAccess=true}
 * （扫描/监听跳过该设置，改为浏览对账）。不含实时监听、扫描间隔、落盘加密配置项。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/25
 */
@StoragePlugin(
        identifier = "LocalDirect",
        name = "本地挂载",
        description = "把服务器本地真实目录直接挂进网盘：不建后台索引，浏览时实时列目录，增删改查直接读写真实文件系统，外部改动刷新即可见。",
        icon = "icon-bendicunchu1",
        schemaResource = "classpath:schema/localdirect-schema.json"
)
public class LocalDirectStorageOperationService extends LocalMountStorageOperationService {

    public LocalDirectStorageOperationService() {
        super();
    }

    public LocalDirectStorageOperationService(StorageConfig config) {
        super(config);
    }

    @Override
    public boolean isDirectAccess() {
        return true;
    }

    @Override
    public boolean isRealtimeWatchSupported() {
        return false;
    }

    @Override
    public boolean supportsWatchRealtime() {
        return false;
    }
}
