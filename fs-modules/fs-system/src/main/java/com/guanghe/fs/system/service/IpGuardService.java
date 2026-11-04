package com.guanghe.fs.system.service;

import com.guanghe.fs.system.domain.dto.IpGuardEditCmd;
import com.guanghe.fs.system.domain.vo.IpGuardConfigVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * IP 安全防护业务接口
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
public interface IpGuardService {

    /**
     * 读取防护配置（仅系统管理员）
     *
     * @return 模式 + 两份名单 + 当前请求来源 IP
     */
    IpGuardConfigVO getConfig();

    /**
     * 保存防护配置（仅系统管理员，整份覆盖）
     * <p>
     * 会拒绝让管理员把自己关在门外的保存：白名单不含当前操作 IP、或黑名单包含当前操作 IP。
     *
     * @param cmd 配置命令
     */
    void saveConfig(IpGuardEditCmd cmd);

    /**
     * 该来源 IP 当前是否被允许登录并使用系统
     *
     * @param clientIp 客户端 IP，取不到传 null（白名单模式下按不允许处理）
     */
    boolean isAllowed(String clientIp);

    /**
     * 不允许时抛出业务异常（code 403，文案按白/黑名单区分），供 HTTP 入口使用
     */
    void assertAllowed(String clientIp);

    /**
     * 取用于名单判定的客户端 IP，与 {@link #isAllowed(String)} 的口径必须一致。
     * <p>
     * 直接走 {@code IpUtils.getIpAddr(request)}：与登录防爆破、注册限流、操作日志同一口径，
     * 保证各层防护看到的是同一个地址。默认只取 TCP 层地址，因为 {@code X-Forwarded-For} 是
     * 客户端可以自己填的请求头，每换一个值就能绕过名单。只有确认部署在会覆写该头的反向代理
     * 后面时，才用 {@code security.trust-proxy=true} 改为取代理写好的真实来源。
     *
     * @return 取不到请求时返回 null
     */
    String resolveClientIp(HttpServletRequest request);
}
