package com.guanghe.fs.framework.common.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 客户端 IP 来源口径：按 IP 记账的防护（登录防爆破、注册限流、IP 黑白名单、文件收集访问码）
 * 全部依赖这里，采信可自造的转发头等于让攻击者每请求换一个新计数桶。
 * <p>
 * 覆盖：默认忽略全部转发头、开启反代信任后取 X-Forwarded-For 首跳、头缺失/无效时回落 TCP 层地址。
 */
class IpUtilsTest {

    private static final String TCP_IP = "203.0.113.9";

    @AfterEach
    void resetTrustProxySwitch() {
        IpUtils.setTrustProxyHeaders(false);
    }

    @Test
    void forgedForwardedHeadersAreIgnoredByDefault() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("X-Real-IP", "198.51.100.2");
        request.addHeader("Proxy-Client-IP", "198.51.100.3");
        request.addHeader("WL-Proxy-Client-IP", "198.51.100.4");
        request.addHeader("HTTP_CLIENT_IP", "198.51.100.5");
        request.addHeader("HTTP_X_FORWARDED_FOR", "198.51.100.6");

        assertEquals(TCP_IP, IpUtils.getIpAddr(request));
    }

    @Test
    void forwardedHeaderChainIsUsedOnceTrustProxyIsEnabled() {
        IpUtils.setTrustProxyHeaders(true);
        MockHttpServletRequest request = request();
        // 多次代理后是「客户端自造值, 上一跳, 最新一跳」，取首跳是历史约定
        request.addHeader("X-Forwarded-For", "198.51.100.1, 10.0.0.2");
        assertEquals("198.51.100.1", IpUtils.getIpAddr(request));

        MockHttpServletRequest realIp = request();
        realIp.addHeader("X-Real-IP", "198.51.100.7");
        assertEquals("198.51.100.7", IpUtils.getIpAddr(realIp));
    }

    @Test
    void placeholderHeaderValuesFallBackToTcpPeerAddress() {
        IpUtils.setTrustProxyHeaders(true);
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "unknown");
        assertEquals(TCP_IP, IpUtils.getIpAddr(request));
    }

    @Test
    void plainRequestUsesTcpPeerAddressInBothModes() {
        assertEquals(TCP_IP, IpUtils.getIpAddr(request()));
        IpUtils.setTrustProxyHeaders(true);
        assertEquals(TCP_IP, IpUtils.getIpAddr(request()));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(TCP_IP);
        return request;
    }
}
