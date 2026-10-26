package com.guanghe.fs.service.alist;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.guanghe.fs.system.constant.UserStatus;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AList 协议凭证：登录 token 与播放直链签名。
 * <p>
 * 为什么不用 Sa-Token 自己签：AList 客户端把 <b>裸 token</b> 直接塞进
 * {@code Authorization} 头（没有 {@code Bearer } 前缀，也没有 token-name 约定），
 * 与网盘 Web 端的 Sa-Token 会话共用一套键会互相覆盖。这里用独立 HMAC 头，
 * 过滤器校验通过后再桥接 Sa-Token（见 {@link AlistAuthFilter}）。
 * <p>
 * 两类凭证：
 * <ul>
 *   <li>{@link #issueToken} API token —— 30 天，载荷里带「当前密码哈希摘要」，
 *       改密码后旧 token 立即失效（不需要维护撤销名单）；</li>
 *   <li>{@link #signPath} 播放直链签名 —— 12 小时，绑定 userId + 路径，
 *       只在 {@code /d/**} 上校验（播放器可能连续发几十个 Range 请求，不做 DB 校验）。</li>
 * </ul>
 * 签名密钥为进程启动时随机生成：重启后旧 token/直链失效，客户端重新登录即可。
 * 单机部署下这是最省事且不留密钥文件的做法。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlistTokenService {

    /** API token 有效期：30 天（与 Sa-Token timeout 对齐，客户端不必频繁重登） */
    private static final long API_TOKEN_TTL_SECONDS = 30L * 24 * 3600;

    /** 播放直链签名有效期：12 小时（一部电影连播不会中途失效） */
    private static final long RAW_SIGN_TTL_SECONDS = 12L * 3600;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SysUserMapper userMapper;

    /** 进程级随机密钥（32 字节） */
    private final byte[] hmacKey = new byte[32];

    {
        new SecureRandom().nextBytes(hmacKey);
    }

    /**
     * 签发 API token
     *
     * @param userId       用户ID
     * @param passwordHash 库里当前的密码哈希（用于派生失效摘要）
     */
    public String issueToken(String userId, String passwordHash) {
        long expireAt = System.currentTimeMillis() / 1000 + API_TOKEN_TTL_SECONDS;
        String payload = userId + "|" + expireAt + "|" + passwordTag(passwordHash);
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + ENCODER.encodeToString(hmac(payload));
    }

    /**
     * 校验 API token
     *
     * @return 有效返回 userId；签名错/过期/用户被禁用/密码已改返回 null
     */
    public String resolveToken(String token) {
        if (StrUtil.isBlank(token)) {
            return null;
        }
        String raw = token.trim();
        // 兼容部分客户端自作主张加的 Bearer 前缀
        if (raw.regionMatches(true, 0, "Bearer ", 0, 7)) {
            raw = raw.substring(7).trim();
        }
        int dot = raw.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String encodedPayload;
        String encodedSig;
        String payload;
        try {
            encodedPayload = raw.substring(0, dot);
            encodedSig = raw.substring(dot + 1);
            payload = new String(DECODER.decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        if (!constantTimeEquals(hmac(payload), DECODER.decode(encodedSig))) {
            return null;
        }
        String[] parts = payload.split("\\|");
        if (parts.length != 3) {
            return null;
        }
        long expireAt;
        try {
            expireAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (expireAt * 1000 < System.currentTimeMillis()) {
            return null;
        }
        SysUser user = userMapper.selectOneById(parts[0]);
        if (user == null || user.getStatus() == null || user.getStatus() != UserStatus.NORMAL) {
            return null;
        }
        // 密码变更即失效：把库里的哈希再摘要一次比对，避免 token 里直接携带密码哈希
        if (!passwordTag(user.getPassword()).equals(parts[2])) {
            return null;
        }
        return user.getId();
    }

    /**
     * 为直链签名：绑定 userId + 路径 + 过期时间。
     * <p>
     * 格式与 API token 一致（base64url(载荷).base64url(签名)），载荷首段即 userId，
     * 这样验签时无需回溯查库就能恢复用户身份，能直接桥接 Sa-Token 上下文。
     *
     * @param userId 用户ID
     * @param path   AList 路径（形如 /电影/a.mp4）
     * @return 作为 {@code ?sign=} 参数值
     */
    public String signPath(String userId, String path) {
        long expireAt = System.currentTimeMillis() / 1000 + RAW_SIGN_TTL_SECONDS;
        String payload = userId + "\n" + expireAt + "\n" + path;
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + ENCODER.encodeToString(hmac(payload));
    }

    /**
     * 校验直链签名
     *
     * @param sign 客户端带来的 sign 参数
     * @param path 本次请求的 AList 路径（必须与签名时一致，防止一签多用）
     * @return 有效返回 userId；签名错/过期/路径不符返回 null
     */
    public String verifySign(String sign, String path) {
        if (StrUtil.isBlank(sign)) {
            return null;
        }
        int dot = sign.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String payload;
        try {
            payload = new String(DECODER.decode(sign.substring(0, dot)), StandardCharsets.UTF_8);
            if (!constantTimeEquals(hmac(payload), DECODER.decode(sign.substring(dot + 1)))) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        String[] parts = payload.split("\n", 3);
        if (parts.length != 3) {
            return null;
        }
        long expireAt;
        try {
            expireAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (expireAt * 1000 < System.currentTimeMillis()) {
            return null;
        }
        return parts[2].equals(path) ? parts[0] : null;
    }

    /** 密码哈希摘要（token 内的失效标记） */
    private String passwordTag(String passwordHash) {
        if (StrUtil.isBlank(passwordHash)) {
            return "none";
        }
        return DigestUtil.sha256Hex(passwordHash).substring(0, 12);
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
