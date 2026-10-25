package com.guanghe.fs.service.webdav;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * /dav 请求路径归一化：把连续斜杠折叠成单个，再交给后续过滤器与控制器。
 * <p>
 * 为什么需要：不少 TV / Android WebDAV 客户端不是直接用 PROPFIND 返回的子项 href，
 * 而是用「集合 href + "/" + 文件名」拼 URL。根集合的 href 按 RFC 4918 是 {@code /dav/}，
 * 两者一拼就得到 {@code /dav//视频.mp4}。sa-token 防火墙把含 {@code //} 的 URL 判为
 * 「非法请求」并返回 200 + text/plain（几十字节文本），播放器拿到的不是视频流，
 * 表现为「点开一直加载、网速只有几 B/s」。
 * <p>
 * 该过滤器必须排在 sa-token 上下文过滤器（-104）与防火墙（-103）之前，
 * 否则请求在被改写前就已经被拦下（见 {@link com.guanghe.fs.service.web.WebDavFilterConfig} 的 order 说明）。
 * 只折叠斜杠，不动 {@code .} / {@code ..} 段——路径穿越仍由后续安全校验拦截。
 */
@Slf4j
@Component
public class DavPathNormalizeFilter implements Filter {

    private static final String DAV_PREFIX = "/dav";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req)) {
            chain.doFilter(request, response);
            return;
        }
        String uri = req.getRequestURI();
        if (uri == null || uri.indexOf("//") < 0) {
            chain.doFilter(request, response);
            return;
        }
        String contextPath = req.getContextPath() == null ? "" : req.getContextPath();
        String path = contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
        if (!path.startsWith(DAV_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String normalizedPath = collapseSlashes(path);
        if (normalizedPath.equals(path)) {
            chain.doFilter(request, response);
            return;
        }
        String normalizedUri = contextPath + normalizedPath;
        log.debug("WebDAV 路径归一化: {} -> {}", uri, normalizedUri);
        chain.doFilter(new NormalizedPathRequest(req, normalizedUri, normalizedPath), response);
    }

    /** 连续斜杠折叠为单个（含末尾多余斜杠保留一个的场景由下游自行处理） */
    private static String collapseSlashes(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        boolean prevSlash = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if (!prevSlash) {
                    sb.append(c);
                }
                prevSlash = true;
            } else {
                sb.append(c);
                prevSlash = false;
            }
        }
        return sb.toString();
    }

    /** 只改写路径相关方法，其余头/参数/输入流沿用原请求 */
    private static final class NormalizedPathRequest extends HttpServletRequestWrapper {

        private final String uri;
        private final String servletPath;

        private NormalizedPathRequest(HttpServletRequest request, String uri, String servletPath) {
            super(request);
            this.uri = uri;
            this.servletPath = servletPath;
        }

        @Override
        public String getRequestURI() {
            return uri;
        }

        @Override
        public String getServletPath() {
            return servletPath;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            String scheme = getScheme();
            int port = getServerPort();
            if (port > 0) {
                url.append(scheme).append("://").append(getServerName()).append(':').append(port);
            } else {
                url.append(scheme).append("://").append(getServerName());
            }
            url.append(uri);
            return url;
        }
    }
}
