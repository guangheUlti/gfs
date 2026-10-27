package com.guanghe.fs.storage.plugin.core;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.dto.HostKeyInfo;

/**
 * 主机密钥信任能力（可选能力，由需要校验服务器身份的存储插件实现）
 *
 * @Author: guangheUlti
 * @Date: 2026/10/27
 */
public interface IHostKeyTrustService {

    /**
     * 仅完成传输层握手，探测服务器主机密钥（不认证、不读写文件）
     *
     * @param config 存储配置
     * @return 主机密钥信息（含当前是否已受信任）
     */
    HostKeyInfo probeHostKey(StorageConfig config);

    /**
     * 再次握手校验指纹后，将主机密钥写入 known_hosts
     *
     * @param config              存储配置
     * @param expectedFingerprint 用户在界面上确认过的 SHA256 指纹
     * @return 写入后的主机密钥信息
     */
    HostKeyInfo trustHostKey(StorageConfig config, String expectedFingerprint);
}
