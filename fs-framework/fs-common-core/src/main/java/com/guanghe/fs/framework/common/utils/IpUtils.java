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
            ip = request.getRemoteAddr();
            if (LOCALHOST_IP.equals(ip) || LOCALHOST_IPV6.equals(ip)) {
                // 根据网卡取本机配置的IP
                try {
                    InetAddress inet = InetAddress.getLocalHost();
                    ip = inet.getHostAddress();
                } catch (UnknownHostException e) {
                    log.error("获取本机IP失败", e);
                }
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

