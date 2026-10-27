package com.guanghe.fs.service.alist;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.manager.ProtocolReadOnlyService;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.guanghe.fs.service.webdav.DavUserAuthenticator;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * AList 协议认证/只读过滤器：拦 /api/auth/*、/api/public/*、/api/me、/api/fs/*、/d/*。
 * <p>
 * 为什么单独一个过滤器而不是复用 {@link com.guanghe.fs.service.webdav.DavAuthFilter}：
 * <ul>
 *   <li>凭证形态不同——AList 客户端发裸 token（无 {@code Bearer } 前缀），WebDAV 发 Basic；</li>
 *   <li>失败语义不同——AList 把错误放在响应信封的 {@code code} 里且 HTTP 恒 200，
 *       WebDAV 必须回 401 + {@code WWW-Authenticate}；</li>
 *   <li>路径白名单不同——登录接口自身不能要求 token。</li>
 * </ul>
 * 放行顺序：
 * <ol>
 *   <li>服务停用 → 503（不卸载 controller 映射）</li>
 *   <li>{@code /d/**} 播放直链：带 {@code ?sign=} 先验签；未带签名且游客可用时按公开账号放行</li>
 *   <li>{@code /api/auth/login}、{@code /api/public/settings}：无需 token</li>
 *   <li>其余：验 token；游客模式下无凭证的读操作（list/get/dirs/search）按公开账号放行；
 *       只读模式拒绝写操作 → 桥接 Sa-Token 供 service 层使用</li>
 * </ol>
 * 游客不是「绕过身份」，而是「以公开账号的身份只读」：业务层全部按登录用户过滤，
 * 匿名请求不桥接就查不到文件。公开账号由 {@link AlistGuestResolver} 解析与校验。
 * 注意：请求 URI 里的 {@code +} 先转义再解码，否则文件名含加号时与签名时的路径不一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlistAuthFilter implements Filter {

    /** 写操作路径后缀（/api/fs/{op}），只读模式下拒绝 */
    private static final Set<String> WRITE_OPS = Set.of(
            "mkdir", "rename", "move", "copy", "remove", "put", "form");

    /**
     * 游客只读放行的读操作（/api/fs/{op}）。
     * <p>
     * 只放读、绝不放写：TVBox/影视仓 等客户端不登录，靠这几个接口列目录、取直链。
     */
    private static final Set<String> GUEST_READ_OPS = Set.of(
            "list", "get", "dirs", "search");

    /** 无需 token 的路径：登录自身与公开信息 */
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/api/public/settings");

    private final AlistGuestResolver guestResolver;
    private final ProtocolReadOnlyService protocolReadOnlyService;
    private final AlistTokenService tokenService;
    private final DavUserAuthenticator authenticator;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        ServiceSetting setting = guestResolver.current();
        boolean enabled = setting != null && setting.getEnabled() != null && setting.getEnabled() == 1;
        if (!enabled) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write("{\"code\":500,\"message\":\"AList service is disabled\",\"data\":null}");
            return;
        }

        String path = relativePath(req);
        // 游客可用的账号 ID；null = 开关关闭 / 未绑定 / 账号已禁用
        String guestUserId = guestResolver.resolve(setting);

        // 播放直链：带签名先验签（登录用户生成的直链绑定其本人，不能被游客身份覆盖）；
        // 未带签名时，游客可用就按公开账号放行（TVBox 拼的是裸 /d/路径）
        if (path.startsWith("/d/")) {
            String sign = req.getParameter("sign");
            if (StrUtil.isNotBlank(sign)) {
                String userId = tokenService.verifySign(sign, "/" + decode(path.substring(3)));
                if (userId == null) {
                    AlistResponse.unauthorized(resp);
                    return;
                }
                authenticator.bridgeSaToken(userId);
                chain.doFilter(request, response);
                return;
            }
            if (guestUserId != null) {
                authenticator.bridgeSaToken(guestUserId);
                chain.doFilter(request, response);
                return;
            }
            AlistResponse.unauthorized(resp);
            return;
        }

        if (PUBLIC_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        String userId = tokenService.resolveToken(req.getHeader("Authorization"));
        if (userId == null) {
            // 游客只读：仅「没带凭证 + 读操作」才放行；带了错 token 仍按认证失败处理，
            // 免得客户端误以为自己的凭证有效
            if (guestUserId != null && StrUtil.isBlank(req.getHeader("Authorization")) && isGuestReadOp(path)) {
                log.debug("AList 游客只读: path={}, ip={}", path, req.getRemoteAddr());
                authenticator.bridgeSaToken(guestUserId);
                chain.doFilter(request, response);
                return;
            }
            AlistResponse.unauthorized(resp);
            return;
        }

        // 只读模式：认证通过后才判（未认证不回 403，避免泄露服务配置）
        if (isWriteOp(path) && protocolReadOnlyService.isReadOnly(ServiceSettingService.TYPE_ALIST)) {
            log.debug("AList 只读模式拒绝写请求: path={}", path);
            AlistResponse.readOnly(resp);
            return;
        }

        authenticator.bridgeSaToken(userId);
        chain.doFilter(request, response);
    }

    /** 去掉 contextPath 的请求路径 */
    private String relativePath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();
        if (StrUtil.isNotEmpty(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }

    /** /api/fs/{op} 且 op 属于写操作 */
    private boolean isWriteOp(String path) {
        String prefix = "/api/fs/";
        if (!path.startsWith(prefix)) {
            return false;
        }
        String op = path.substring(prefix.length());
        int slash = op.indexOf('/');
        if (slash >= 0) {
            op = op.substring(0, slash);
        }
        return WRITE_OPS.contains(op.toLowerCase(Locale.ROOT));
    }

    /** /api/fs/{op} 且 op 属于游客可读的读操作 */
    private boolean isGuestReadOp(String path) {
        String prefix = "/api/fs/";
        if (!path.startsWith(prefix)) {
            return false;
        }
        String op = path.substring(prefix.length());
        int slash = op.indexOf('/');
        if (slash >= 0) {
            op = op.substring(0, slash);
        }
        return GUEST_READ_OPS.contains(op.toLowerCase(Locale.ROOT));
    }

    /** URI 解码：先把 + 转义，避免文件名里的加号被当成空格 */
    static String decode(String raw) {
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
    }
}
