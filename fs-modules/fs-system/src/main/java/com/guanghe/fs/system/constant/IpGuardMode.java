package com.guanghe.fs.system.constant;

/**
 * IP 安全防护模式（互斥：同一时刻只有一种名单生效）
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
public enum IpGuardMode {

    /** 不启用：所有 IP 均可登录与使用 */
    OFF,

    /** 白名单：只有名单内的 IP 能登录并使用系统 */
    WHITE,

    /** 黑名单：名单内的 IP 被禁止登录与使用 */
    BLACK;

    /**
     * 解析模式字符串；为空或未知一律按 {@link #OFF} 处理。
     * <p>
     * 兜底方向选 OFF 而不是 WHITE：配置读不出来的时候不能把所有人挡在门外。
     */
    public static IpGuardMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        String normalized = value.trim().toUpperCase();
        for (IpGuardMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return OFF;
    }
}
