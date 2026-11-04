package com.guanghe.fs.framework.common.utils;

import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IP工具类
 *
 * @Author: guanghe
 * @Date: 2025/9/25 14:35
 */
@Slf4j
@Component
public class IpUtils {

    private static final String UNKNOWN = "unknown";
    private static final String LOCALHOST_IP = "127.0.0.1";
    private static final String LOCALHOST_IPV6 = "0:0:0:0:0:0:0:1";

    /**
     * 是否信任反向代理追加的转发请求头。
     * <p>
     * 默认 false：只认 TCP 层对端地址。因为 X-Forwarded-For 等头是客户端可自造的，且即使经过
     * 反向代理（Nginx 的 $proxy_add_x_forwarded_for 是「追加」而非「覆盖」），最前几跳仍是请求方
     * 自己填的值。按 IP 记账的防护（登录防爆破、注册限流、IP 黑白名单、文件收集访问码……）一旦
     * 采信这些头，攻击者每请求换一个伪造 IP 就能无限拿到新的计数桶，防护形同不存在。
     * <p>
     * 只有应用确实坐在可信代理后面、且代理把转发头写对时，才由 {@code security.trust-proxy} 置 true。
     */
    private static volatile boolean trustProxyHeaders = false;

    public static void setTrustProxyHeaders(boolean trustProxyHeaders) {
        IpUtils.trustProxyHeaders = trustProxyHeaders;
    }

    /**
     * 获取客户端IP地址
     */
    public static String getIpAddr() {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return LOCALHOST_IP;
        }
        return getIpAddr(request);
    }

    /**
     * 获取客户端IP地址
     */
    public static String getIpAddr(HttpServletRequest request) {
        if (request == null) {
            return LOCALHOST_IP;
        }

        if (!trustProxyHeaders) {
            return remoteAddr(request);
        }

        String ip = request.getHeader("X-Forwarded-For");
        if (isValidIp(ip)) {
            // 多次反向代理后会有多个ip值，第一个ip才是真实ip
            int index = ip.indexOf(',');
            if (index != -1) {
                ip = ip.substring(0, index);
            }
        }

        if (!isValidIp(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (!isValidIp(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (!isValidIp(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (!isValidIp(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (!isValidIp(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (!isValidIp(ip)) {
            ip = remoteAddr(request);
        }

        return ip;
    }

    /**
     * TCP 层对端地址。本机回环时换成网卡地址，便于本地自测的日志里看出来源。
     */
    private static String remoteAddr(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (LOCALHOST_IP.equals(ip) || LOCALHOST_IPV6.equals(ip)) {
            try {
                ip = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                log.error("获取本机IP失败", e);
            }
        }
        return ip;
    }

    /**
     * 判断IP是否有效
     */
    private static boolean isValidIp(String ip) {
        return ip != null && !ip.isEmpty() && !UNKNOWN.equalsIgnoreCase(ip);
    }

    /**
     * 获取User-Agent
     */
    public static String getUserAgent() {
        HttpServletRequest request = getRequest();
        return request != null ? request.getHeader("User-Agent") : "";
    }

    /**
     * 解析User-Agent获取浏览器信息
     */
    public static String getBrowser() {
        String userAgentStr = getUserAgent();
        if (userAgentStr == null || userAgentStr.isEmpty()) {
            return "未知";
        }

        UserAgent userAgent = UserAgentUtil.parse(userAgentStr);
        String browserName = userAgent.getBrowser().getName();
        String browserVersion = userAgent.getVersion();
        // 脚本客户端等非浏览器 UA 解析不出版本号，直接拼接会得到 "Unknown null" 这种脏数据
        if (browserVersion == null || browserVersion.isEmpty()) {
            return browserName;
        }
        return browserName + " " + browserVersion;
    }

    /**
     * 解析User-Agent获取操作系统信息
     */
    public static String getOs() {
        String userAgentStr = getUserAgent();
        if (userAgentStr == null || userAgentStr.isEmpty()) {
            return "未知";
        }
        UserAgent userAgent = UserAgentUtil.parse(userAgentStr);
        return userAgent.getOs().getName();
    }

    /**
     * 获取当前请求
     */
    private static HttpServletRequest getRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }
}

