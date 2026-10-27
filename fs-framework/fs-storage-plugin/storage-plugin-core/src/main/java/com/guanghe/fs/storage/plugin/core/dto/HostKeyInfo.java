package com.guanghe.fs.storage.plugin.core.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 存储服务器主机密钥信息
 *
 * @Author: guangheUlti
 * @Date: 2026/10/27
 */
@Data
@Builder
public class HostKeyInfo {

    /** 服务器地址 */
    private String host;

    /** 端口 */
    private int port;

    /** 密钥类型（如 ssh-ed25519、ssh-rsa） */
    private String keyType;

    /** OpenSSH 风格 SHA256 指纹 */
    private String fingerprint;

    /** known_hosts 文件路径 */
    private String knownHostsPath;

    /** 该密钥是否已在 known_hosts 中受信任 */
    private boolean trusted;
}
