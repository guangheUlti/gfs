package com.guanghe.fs.system.web;

import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.JsonUtils;
import com.guanghe.fs.system.service.IpGuardService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 安全防护过滤器：在认证之前按来源 IP 拦下名单外的请求。
 * <p>
 * 放在过滤器而不是拦截器，是因为它必须同时覆盖 {@code /apis/**}（网盘本体，走拦截器）
 * 和 WebDAV / OSS / AList 直链（这些路径在 {@code security.excludes} 里豁免了登录校验，
 * 拦截器根本不会执行）。挡在认证前面，名单外的 IP 连密码校验都触发不了。
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
@Slf4j
@RequiredArgsConstructor
public class IpGuardFilter extends OncePerRequestFilter {

    /** 同一个被拒 IP 的告警间隔：客户端（挂载盘、同步软件）会秒级重试，不能刷满日志 */
    private static final long LOG_INTERVAL_MS = 60_000L;

    /** 告警记录条数上限，超过后清掉过期条目，避免被轮换的来源 IP 撑爆内存 */
    private static final int LOG_TRACKER_MAX = 1024;

    private final IpGuardService ipGuardService;

    /** 来源 IP -> 最近一次告警时间 */
    private final Map<String, Long> lastLoggedAt = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String clientIp = ipGuardService.resolveClientIp(request);
        try {
            ipGuardService.assertAllowed(clientIp);
        } catch (BusinessException e) {
            logDenied(clientIp, request, e);
            writeDenied(response, e.getCode(), e.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    private void logDenied(String clientIp, HttpServletRequest request, BusinessException e) {
        long now = System.currentTimeMillis();
        if (lastLoggedAt.size() > LOG_TRACKER_MAX) {
            lastLoggedAt.values().removeIf(time -> time <= now - LOG_INTERVAL_MS);
        }
        Long previous = lastLoggedAt.get(clientIp);
        if (previous != null && now - previous < LOG_INTERVAL_MS) {
            return;
        }
        lastLoggedAt.put(clientIp, now);
        log.warn("IP 防护拒绝请求: ip={}, uri={}, reason={}", clientIp, request.getRequestURI(), e.getMessage());
    }

    /**
     * 回 403 + 统一响应体：网盘前端按 msg 提示，curl / rclone 这类客户端则按状态码判断，两边都能看懂。
     */
    private void writeDenied(HttpServletResponse response, Integer code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonUtils.toJsonString(Result.error(code, message, null)));
    }
}
