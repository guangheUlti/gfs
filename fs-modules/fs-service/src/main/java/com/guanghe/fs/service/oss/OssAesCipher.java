package com.guanghe.fs.service.oss;

import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * OSS Secret Key 存储加密（AES-256-GCM）。
 * <p>
 * SigV4 验签需还原 Secret Key 明文参与 HMAC-SHA256 链（BCrypt 不可逆，无法使用），
 * 故密文可逆：密文格式 Base64(iv[12] || ciphertext+tag[16])。
 * 主密钥来自配置 {@code oss.aes-key}（32 字节字符串）；缺失时启动自动生成随机密钥并告警——
 * 随机密钥仅保存在内存，重启后历史密文将无法解密（该密钥对自然失效），需重新签发。
 */
@Slf4j
@Component
public class OssAesCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec0 keySpec;
    private final SecureRandom random = new SecureRandom();

    public OssAesCipher(@Value("${oss.aes-key:}") String configuredKey) {
        String key = configuredKey == null ? "" : configuredKey.trim();
        if (key.isEmpty()) {
            key = RandomUtil.randomStringUpper(32);
            log.warn("未配置 oss.aes-key，已生成本次运行随机主密钥；重启后已签发的 OSS Secret Key 将无法解密，请尽快在 application.yml 固化 32 位密钥");
        } else if (key.length() != 32) {
            log.warn("oss.aes-key 长度应为 32 字节（AES-256），当前 {} 字节，将按 SHA-256 摘要对齐", key.length());
            key = cn.hutool.crypto.digest.DigestUtil.sha256Hex(key).substring(0, 32);
        }
        this.keySpec = new SecretKeySpec0(key.getBytes(StandardCharsets.UTF_8));
    }

    /** 加密：返回 Base64(iv || ciphertext+tag) */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec.spec(), new javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("OSS Secret Key 加密失败", e);
        }
    }

    /** 解密：主密钥不匹配或密文被篡改时返回 null（调用方按密钥失效处理） */
    public String decrypt(String base64Cipher) {
        try {
            byte[] all = Base64.getDecoder().decode(base64Cipher);
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, IV_LENGTH);
            byte[] cipherText = java.util.Arrays.copyOfRange(all, IV_LENGTH, all.length);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec.spec(), new javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 简单包装，避免与 javax.crypto.spec.SecretKeySpec 在同文件混淆 */
    private record SecretKeySpec0(byte[] encoded) {
        javax.crypto.spec.SecretKeySpec spec() {
            return new javax.crypto.spec.SecretKeySpec(encoded, "AES");
        }
    }
}
