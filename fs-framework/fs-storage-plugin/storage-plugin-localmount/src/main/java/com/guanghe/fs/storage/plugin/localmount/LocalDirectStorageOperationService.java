package com.guanghe.fs.storage.plugin.localmount;

import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;

/**
 * 本地挂载（LocalDirect，直读）存储插件
 * <p>
 * 复用 {@link LocalMountStorageOperationService} 的本地文件读写实现（根路径归一化、防逃逸、写穿透），
 * 但完全无状态：不建索引、不落 file_info，真实文件系统为唯一真相源，
 * 浏览与增删改都直接作用于真实目录，外部改动刷新即可见。
 * <p>
 * 能力位：{@code isMountMode=true}（复用挂载写穿透/清理语义）+ {@code isDirectAccess=true}
 * + {@code isStatelessRemote=true}。
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
    public boolean isStatelessRemote() {
        // 不落库：真实文件系统为唯一真相源，浏览与增删改都直接作用于本地目录
        return true;
    }
}
