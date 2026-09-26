package com.guanghe.fs.service.oss;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS SigV4 验签（自实现，不依赖 AWS SDK）。
 * <p>
 * 仅支持 Authorization 头方式：{@code AWS4-HMAC-SHA256 Credential=AK/date/region/s3/aws4_request,
 * SignedHeaders=..., Signature=...}。查询串鉴权（presigned URL）不支持，返回 null 视为未提供凭证。
 * <p>
 * Canonical URI 契约：网关挂 /oss/**，客户端按 path-style 计算
 * {@code /oss/<bucket>/<key>}（ListBuckets 为 /oss/），本类按传入的 canonicalUri 直接使用。
 * 请求体签名支持 UNSIGNED-PAYLOAD（S3 惯例）与 sha256-hex 两种。
 */
public final class OssSigV4Verifier {

    /** 十六进制小写 */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** x-amz-date / Date 解析：yyyyMMdd'T'HHmmss'Z'（SigV4 惯例，UTC） */
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private OssSigV4Verifier() {
    }

    /** 验签结果：null = 请求未提供 SigV4 头（交由调用方 403）；签名/时间问题抛 IllegalArgumentException */
    public static String verify(HttpServletRequest request, String secretKey, String region, String bucketPath)
            throws Exception {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("AWS4-HMAC-SHA256 ")) {
            return null;
        }
        ParsedAuth parsed = parseAuth(auth);
        if (parsed == null) {
            throw new IllegalArgumentException("malformed Authorization header");
        }

        // 1. 时间戳：优先 x-amz-date，兼容 Date 头
        String amzDate = request.getHeader("x-amz-date");
        if (amzDate == null || amzDate.isEmpty()) {
            amzDate = request.getHeader("Date");
        }
        if (amzDate == null || amzDate.isEmpty()) {
            throw new IllegalArgumentException("missing x-amz-date header");
        }
        Instant requestTime;
        try {
            requestTime = Instant.from(AMZ_DATE.parse(amzDate));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid x-amz-date format, expect yyyyMMdd'T'HHmmss'Z'");
        }
        // 2. 时钟偏移 ±15 分钟
        long skew = Math.abs(requestTime.toEpochMilli() - System.currentTimeMillis());
        if (skew > 15 * 60 * 1000L) {
            throw new IllegalArgumentException("request time deviates beyond 15 minutes");
        }
        // 3. scope 日期与 x-amz-date 一致
        String credentialScope = parsed.accessDate + "/" + region + "/s3/aws4_request";
        if (!parsed.scope.equals(credentialScope)) {
            throw new IllegalArgumentException("credential scope mismatch");
        }

        // 4. 重建规范化请求
        List<String> signedHeaderList = List.of(parsed.signedHeaders.split(";"));
        String canonicalRequest = canonicalRequest(request, signedHeaderList, amzDate, bucketPath);
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + credentialScope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        // 5. HMAC 链推导签名密钥并比对
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), parsed.accessDate);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        String expected = hex(hmac(kSigning, stringToSign));
        if (!constantTimeEquals(expected, parsed.signature)) {
            throw new IllegalArgumentException("signature mismatch");
        }
        return parsed.accessKeyId;
    }

    /** 解析 Authorization 头 */
    private static ParsedAuth parseAuth(String auth) {
        // AWS4-HMAC-SHA256 Credential=AK/20260926/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=ab...
        String body = auth.substring("AWS4-HMAC-SHA256 ".length()).trim();
        String credential = null;
        String signedHeaders = null;
        String signature = null;
        for (String part : body.split(",")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = p.substring(0, eq).trim();
            String v = p.substring(eq + 1).trim();
            switch (k) {
                case "Credential" -> credential = v;
                case "SignedHeaders" -> signedHeaders = v;
                case "Signature" -> signature = v;
                default -> {
                }
            }
        }
        if (credential == null || signedHeaders == null || signature == null) {
            return null;
        }
        String[] credParts = credential.split("/");
        if (credParts.length != 5) {
            return null;
        }
        return new ParsedAuth(credParts[0], credParts[1],
                credential.substring(credential.indexOf('/') + 1), signedHeaders, signature);
    }

    private record ParsedAuth(String accessKeyId, String accessDate, String scope,
                              String signedHeaders, String signature) {
    }

    /** 重建 CanonicalRequest（签名头列表以客户端 SignedHeaders 为准逐个取值） */
    private static String canonicalRequest(HttpServletRequest request, List<String> signedHeaders,
                                           String amzDate, String canonicalUri) throws Exception {
        StringBuilder sb = new StringBuilder();
        String method = request.getMethod().toUpperCase();
        sb.append(method).append('\n');
        sb.append(canonicalUri).append('\n');

        // Canonical query string：getQueryString 返回的是原始编码串，需先解码再统一按 RFC3986 重编码
        Map<String, List<String>> params = new TreeMap<>();
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                params.computeIfAbsent(urlDecode(k), x -> new ArrayList<>()).add(urlDecode(v));
            }
        }
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, List<String>> e : params.entrySet()) {
            String ek = uriEncode(e.getKey());
            List<String> vs = e.getValue();
            vs.sort(String::compareTo);
            for (String v : vs) {
                if (qs.length() > 0) {
                    qs.append('&');
                }
                qs.append(ek).append('=').append(uriEncode(v));
            }
        }
        sb.append(qs).append('\n');

        // Canonical headers：仅 SignedHeaders 列出的头，小写、trim、折叠连续空格
        for (String name : signedHeaders) {
            if ("host".equals(name)) {
                sb.append("host:").append(request.getHeader("Host") == null ? "" : request.getHeader("Host").trim())
                        .append('\n');
                continue;
            }
            List<String> values = new ArrayList<>();
            Enumeration<String> it = request.getHeaders(name);
            while (it.hasMoreElements()) {
                values.add(it.nextElement());
            }
            if (values.isEmpty()) {
                // 签名头缺失：签不过，直接失败
                throw new IllegalArgumentException("signed header missing: " + name);
            }
            sb.append(name).append(':');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(values.get(i).trim().replaceAll(" +", " "));
            }
            sb.append('\n');
        }

        // SignedHeaders 行
        sb.append(String.join(";", signedHeaders)).append('\n');

        // Payload hash：UNSIGNED-PAYLOAD 或 x-amz-content-sha256 声明值
        String declared = request.getHeader("x-amz-content-sha256");
        if (declared != null && !declared.isEmpty()) {
            if ("UNSIGNED-PAYLOAD".equalsIgnoreCase(declared)) {
                sb.append("UNSIGNED-PAYLOAD");
            } else {
                sb.append(declared.trim());
            }
        } else {
            sb.append("UNSIGNED-PAYLOAD");
        }
        return sb.toString();
    }

    /** RFC 3986 percent 编码（AWS 惯例：percent 后必须大写 hex，比 URLEncoder 严格） */
    public static String uriEncode(String s) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(Character.toUpperCase(HEX[(b >> 4) & 0xF]))
                        .append(Character.toUpperCase(HEX[b & 0xF]));
            }
        }
        return sb.toString();
    }

    public static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    /** 查询参数解码（失败时按原样返回，保持与客户端一致由签名比对兜底） */
    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
