package com.guanghe.fs.service.alist;

import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.framework.common.utils.JsonUtils;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.mapper.SysUserMapper;
import com.guanghe.fs.service.webdav.DavUserAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AList 认证与元信息接口。
 * <ul>
 *   <li>{@code POST /api/auth/login}：账号密码换 token（与 Web/WebDAV/SFTP/FTP 共用
 *       {@link DavUserAuthenticator}，因此也共用「账号+IP 三次失败锁定」的防暴力计数）；</li>
 *   <li>{@code GET  /api/me}：客户端登录后拉取身份；</li>
 *   <li>{@code GET  /api/public/settings}：AList v3 客户端普遍会先探测这个接口，
 *       缺了它部分播放器（含影视仓）会判定「不是 AList V3」而拒绝添加。</li>
 * </ul>
 * 三个入口的免 token 判定在 {@link AlistAuthFilter} 里（登录与 settings 放行，me 需要 token）。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AlistAuthController {

    private final DavUserAuthenticator authenticator;
    private final AlistTokenService tokenService;
    private final SysUserMapper userMapper;

    /** 登录：{"username":"admin","password":"***"} → {"token":"..."} */
    @PostMapping("/auth/login")
    public void login(@RequestBody(required = false) String body,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        Dict form = parseBody(body);
        String username = StrUtil.trim(form.getStr("username"));
        String password = form.getStr("password");
        if (StrUtil.isBlank(username) || StrUtil.isEmpty(password)) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "username or password is required");
            return;
        }
        // 防暴力计数按「账号+TCP 层 IP」，不取 X-Forwarded-For（请求头可伪造）
        SysUser user = authenticator.authenticate(username, password, request.getRemoteAddr());
        if (user == null) {
            // 不区分「账号不存在 / 密码错 / 已锁定」，具体原因只在日志里
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "username or password is incorrect");
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", tokenService.issueToken(user.getId(), user.getPassword()));
        AlistResponse.ok(response, data);
    }

    /** 当前身份 */
    @GetMapping("/me")
    public void me(HttpServletResponse response) throws IOException {
        String userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsString();
        SysUser user = userMapper.selectOneById(userId);
        if (user == null) {
            AlistResponse.unauthorized(response);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("base_path", "/");
        // role：1 普通用户 / 2 管理员。这里只区分「能看自己网盘」这一件事，不映射后台权限
        data.put("role", 1);
        data.put("permission", 0);
        data.put("disabled", false);
        data.put("sso_id", "");
        AlistResponse.ok(response, data);
    }

    /**
     * 公开信息（客户端据此判定 AList v3 与是否允许游客）。
     * <p>
     * 字段取 AList v3 官方 settings 的子集：客户端（尤其页面端）会读
     * {@code version} / {@code default_page_size} 决定分页与功能开关；
     * 未实现的能力（SSO/LDAP/WebAuthn/游客浏览）一律给 false，免得客户端去调不存在的端点。
     */
    @GetMapping("/public/settings")
    public void settings(HttpServletResponse response) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("site_title", "GFS");
        data.put("announcement", "");
        data.put("allow_indexed", false);
        // 不支持匿名浏览：本服务所有文件接口都要求 token
        data.put("allow_guest", false);
        data.put("allow_mounted", false);
        data.put("allow_register", false);
        data.put("logo", "");
        data.put("title", "GFS");
        data.put("favicon", "");
        data.put("sitemap", false);
        data.put("api_version", "v3");
        // 已实现到 v3.58 的列表分页字段；version 影响客户端是否启用分页读取
        data.put("version", "3.58.0");
        data.put("default_page_size", "200");
        data.put("sso_login", false);
        data.put("sso_login_platform", List.of());
        data.put("ldap_login", false);
        data.put("webauthn_login", false);
        data.put("main_color", "#1890ff");
        AlistResponse.ok(response, data);
    }

    /** 请求体可能是 JSON，也可能是空体；解析失败一律按空表单处理，由调用方报参数错 */
    static Dict parseBody(String body) {
        if (StrUtil.isBlank(body)) {
            return Dict.create();
        }
        try {
            Dict dict = JsonUtils.parseMap(body);
            return dict == null ? Dict.create() : dict;
        } catch (Exception e) {
            return Dict.create();
        }
    }
}
