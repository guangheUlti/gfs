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
import java.util.Locale;
import java.util.Set;

/**
 * WebDAV Basic 认证过滤器：只拦 /dav/*，order 在最前。
 * <ul>
 *   <li>服务停用 → 503（不卸载 controller 映射）</li>
 *   <li>凭证缺失/格式错/校验失败 → 401 + WWW-Authenticate</li>
 *   <li>只读模式且方法是写方法 → 403（见 {@link #READ_METHODS}）</li>
 *   <li>校验成功 → 桥接 Sa-Token（login + setTokenValue，带内存 token 缓存）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DavAuthFilter implements Filter {

    private static final String WWW_AUTHENTICATE = "Basic realm=\"GFS\", charset=\"UTF-8\"";

    /**
     * 只读模式下放行的方法：枚举目录 + 下载/预览均为只读。
     * 其余（PUT/MKCOL/DELETE/MOVE/COPY/PROPPATCH/LOCK/UNLOCK/POST）全部 403。
     */
    private static final Set<String> READ_METHODS = Set.of("OPTIONS", "GET", "HEAD", "PROPFIND");

    /** 可写模式的 Allow（含 WebDAV 扩展方法与 LOCK） */
    private static final String ALLOW = "OPTIONS, GET, HEAD, PUT, POST, PROPFIND, MKCOL, DELETE, MOVE, COPY, LOCK, UNLOCK";

    /** 只读模式的 Allow：不含任何写方法（LOCK 属写语义，也不声明） */
    private static final String ALLOW_READ_ONLY = "OPTIONS, GET, HEAD, PROPFIND";

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

        // 防暴力计数按「账号+IP」：用 TCP 层 remoteAddr，不取 X-Forwarded-For（请求头可伪造，
        // 每请求换一个伪造 IP 就能无限重置计数，等于防护失效）
        var user = authenticator.authenticate(username, password, req.getRemoteAddr());
        if (user == null) {
            unauthorized(resp);
            return;
        }

        boolean readOnly = ServiceSettingService.isReadOnly(setting);

        // OPTIONS 必须在这里答：Spring Boot 默认 dispatch-options-request=false，
        // 请求不会进 DispatcherServlet，而是由 HttpServlet.doOptions 回一张固定的标准方法表
        // （不认识 PROPFIND/COPY/LOCK，也不知道只读模式）。资源管理器/rclone 正是靠 OPTIONS 判能力。
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setHeader("Allow", readOnly ? ALLOW_READ_ONLY : ALLOW);
            resp.setHeader("DAV", readOnly ? "1" : "1, 2");
            resp.setHeader("MS-Author-Via", "DAV");
            resp.setContentLength(0);
            return;
        }

        // 只读模式：认证通过后才判（未认证不泄露服务配置），写方法直接拒，
        // 也不再桥接 token——被拒的写请求无需建会话
        if (readOnly) {
            if (!READ_METHODS.contains(req.getMethod().toUpperCase(Locale.ROOT))) {
                log.debug("WebDAV 只读模式拒绝写请求: method={}, path={}", req.getMethod(), req.getRequestURI());
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                resp.setContentType("text/plain; charset=UTF-8");
                resp.getWriter().write("WebDAV is in read-only mode");
                return;
            }
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
