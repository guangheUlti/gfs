package com.guanghe.fs.service.webdav;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.service.ServiceSettingService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * WebDAV Basic 认证过滤器：只拦 /dav/*，order 在最前。
 * <ul>
 *   <li>服务停用 → 503（不卸载 controller 映射）</li>
 *   <li>凭证缺失/格式错/校验失败 → 401 + WWW-Authenticate</li>
 *   <li>校验成功 → 桥接 Sa-Token（login + setTokenValue，带内存 token 缓存）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DavAuthFilter implements Filter {

    private static final String WWW_AUTHENTICATE = "Basic realm=\"GFS\", charset=\"UTF-8\"";

    private final DavUserAuthenticator authenticator;
    private final ServiceSettingService serviceSettingService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        // 服务停用 → 503（文案由调用方语义自解释，纯短句即可）
        ServiceSetting setting = getByType(ServiceSettingService.TYPE_WEBDAV);
        boolean enabled = setting != null && setting.getEnabled() != null && setting.getEnabled() == 1;
        if (!enabled) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().write("WebDAV service is disabled");
            return;
        }

        String header = req.getHeader("Authorization");
        if (StrUtil.isBlank(header) || !header.startsWith("Basic ")) {
            unauthorized(resp);
            return;
        }
        String decoded;
        try {
            decoded = new String(Base64.decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            unauthorized(resp);
            return;
        }
        int idx = decoded.indexOf(':');
        if (idx <= 0) {
            unauthorized(resp);
            return;
        }
        String username = decoded.substring(0, idx);
        String password = decoded.substring(idx + 1);

        var user = authenticator.authenticate(username, password);
        if (user == null) {
            unauthorized(resp);
            return;
        }

        // 桥接当前用户：后续 service 层 StpUtil.getLoginIdAsString() 正常工作
        authenticator.bridgeSaToken(user.getId());

        // 给 WebDAV 请求设置存储平台上下文（协议端始终走默认存储）
        chain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setHeader("WWW-Authenticate", WWW_AUTHENTICATE);
    }

    private ServiceSetting getByType(String type) {
        try {
            return serviceSettingService.getOne(
                    com.mybatisflex.core.query.QueryWrapper.create()
                            .where(com.guanghe.fs.service.domain.table.ServiceSettingTableDef
                                    .SERVICE_SETTING.SERVICE_TYPE.eq(type)));
        } catch (Exception e) {
            log.warn("读取 WebDAV 服务配置失败，按停用处理", e);
            return null;
        }
    }
}
