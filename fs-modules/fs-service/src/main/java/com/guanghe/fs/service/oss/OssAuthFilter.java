package com.guanghe.fs.service.oss;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.service.domain.OssAccessKey;
import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.mybatisflex.core.query.QueryWrapper;
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
import java.util.concurrent.atomic.AtomicLong;

import static com.guanghe.fs.service.domain.table.ServiceSettingTableDef.SERVICE_SETTING;

/**
 * OSS SigV4 认证过滤器：只拦 /oss/*（order 与 DavAuthFilter 相同，早于常规过滤器）。
 * <ul>
 *   <li>服务停用 → 503</li>
 *   <li>无/错凭证 → 403 + S3 XML 错误体（AccessDenied / InvalidAccessKeyId / SignatureDoesNotMatch）</li>
 *   <li>成功 → 桥接 Sa-Token（复用 DavUserAuthenticator 的 token 缓存），并传递 accessKey 记录上下文</li>
 * </ul>
 * 验签需 Secret Key 明文（HMAC 链不可用哈希），故走 AES-GCM 可逆存储。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssAuthFilter implements Filter {

    /** SigV4 固定 region：网关不区分地域，客户端任填一致即可，这里统一按 us-east-1 校验 */
    public static final String REGION = "us-east-1";

    private final ServiceSettingService serviceSettingService;
    private final OssAccessKeyService ossAccessKeyService;
    private final com.guanghe.fs.service.webdav.DavUserAuthenticator davUserAuthenticator;

    /** lastUsedAt 刷新节流：1 分钟 */
    private static final long TOUCH_INTERVAL_MS = 60_000L;
    private final AtomicLong lastTouch = new AtomicLong(0);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        // 服务停用 → 503
        ServiceSetting setting;
        try {
            setting = serviceSettingService.getOne(
                    new QueryWrapper().where(SERVICE_SETTING.SERVICE_TYPE.eq(ServiceSettingService.TYPE_OSS)));
        } catch (Exception e) {
            log.warn("读取 OSS 服务配置失败，按停用处理", e);
            setting = null;
        }
        boolean enabled = setting != null && setting.getEnabled() != null && setting.getEnabled() == 1;
        if (!enabled) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.setContentType("application/xml; charset=UTF-8");
            resp.getWriter().write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>ServiceUnavailable</Code>"
                    + "<Message>OSS service is disabled</Message></Error>");
            return;
        }

        try {
            authenticate(req, resp);
        } catch (OssAuthException e) {
            writeError(resp, e.status, e.code, e.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String auth = req.getHeader("Authorization");
        if (StrUtil.isBlank(auth) || !auth.startsWith("AWS4-HMAC-SHA256")) {
            // 未携带 SigV4 凭证：按 S3 惯例匿名拒绝
            throw new OssAuthException(HttpServletResponse.SC_FORBIDDEN, "AccessDenied",
                    "Anonymous access is not allowed. Sign requests with AWS Signature Version 4.");
        }
        // 解析 Access Key → 查密钥记录
        ParsedCredential credential;
        try {
            credential = ParsedCredential.parse(auth);
        } catch (IllegalArgumentException e) {
            throw new OssAuthException(HttpServletResponse.SC_BAD_REQUEST, "InvalidArgument", e.getMessage());
        }
        OssAccessKey key = ossAccessKeyService.findActiveByAccessKey(credential.accessKey());
        if (key == null) {
            throw new OssAuthException(HttpServletResponse.SC_FORBIDDEN, "InvalidAccessKeyId",
                    "The AWS Access Key Id you provided does not exist in our records.");
        }
        String secret = ossAccessKeyService.revealSecret(key);
        if (secret == null) {
            log.error("OSS Secret Key 解密失败: keyId={}（主密钥 oss.aes-key 可能已变更）", key.getId());
            throw new OssAuthException(HttpServletResponse.SC_FORBIDDEN, "InvalidAccessKeyId",
                    "The Access Key is no longer valid. Please issue a new one.");
        }

        String bucketPath = bucketPath(req);
        try {
            OssSigV4Verifier.verify(req, secret, REGION, bucketPath);
        } catch (IllegalArgumentException e) {
            throw new OssAuthException(HttpServletResponse.SC_FORBIDDEN, "SignatureDoesNotMatch",
                    e.getMessage() == null ? "signature mismatch" : e.getMessage());
        } catch (Exception e) {
            log.warn("OSS SigV4 验签异常: uri={}", req.getRequestURI(), e);
            throw new OssAuthException(HttpServletResponse.SC_FORBIDDEN, "SignatureDoesNotMatch",
                    "signature verification failed");
        }

        // 桥接 Sa-Token：后续 FileInfoService 依赖 StpUtil.getLoginIdAsString()
        davUserAuthenticator.bridgeSaToken(key.getUserId());

        // lastUsedAt 节流刷新
        long now = System.currentTimeMillis();
        long prev = lastTouch.get();
        if (now - prev > TOUCH_INTERVAL_MS && lastTouch.compareAndSet(prev, now)) {
            ossAccessKeyService.touchLastUsed(key.getId());
        }
    }

    private void writeError(HttpServletResponse resp, int status, String code, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/xml; charset=UTF-8");
        String safeMessage = message == null ? "" : message
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        resp.getWriter().write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code
                + "</Code><Message>" + safeMessage + "</Message></Error>");
    }

    /**
     * Canonical URI：/oss/<bucket>/<key...>（path-style）。
     * Tomcat 的 getRequestURI 未解码，与客户端签名串保持逐字节一致：
     * 仅 Service 根（/oss、/oss/）归一为 "/oss/"，其余路径原样返回（不加尾部斜杠）。
     */
    static String bucketPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();
        if (StrUtil.isNotEmpty(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.isEmpty() || uri.equals("/oss")) {
            return "/oss/";
        }
        return uri;
    }

    /** Authorization 头解析产物 */
    private record ParsedCredential(String accessKey) {

        static ParsedCredential parse(String auth) {
            // AWS4-HMAC-SHA256 Credential=GFSxxx/20260926/us-east-1/s3/aws4_request, SignedHeaders=..., Signature=...
            int schemeEnd = auth.indexOf("AWS4-HMAC-SHA256 ");
            if (schemeEnd < 0) {
                throw new IllegalArgumentException("unsupported authorization scheme");
            }
            String body = auth.substring(schemeEnd + "AWS4-HMAC-SHA256 ".length()).trim();
            String cred = null;
            for (String part : body.split(",")) {
                String p = part.trim();
                if (p.startsWith("Credential=")) {
                    cred = p.substring("Credential=".length()).trim();
                    break;
                }
            }
            if (cred == null) {
                throw new IllegalArgumentException("missing Credential in Authorization header");
            }
            int firstSlash = cred.indexOf('/');
            if (firstSlash <= 0) {
                throw new IllegalArgumentException("malformed Credential element");
            }
            return new ParsedCredential(cred.substring(0, firstSlash));
        }
    }

    /** OSS 认证异常（转 XML 错误响应） */
    static class OssAuthException extends RuntimeException {

        final int status;
        final String code;

        OssAuthException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
