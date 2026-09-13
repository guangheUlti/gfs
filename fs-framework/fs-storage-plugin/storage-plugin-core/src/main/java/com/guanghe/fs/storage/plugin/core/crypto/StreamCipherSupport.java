package com.guanghe.fs.storage.plugin.core.crypto;

import com.guanghe.fs.framework.common.exception.StorageOperationException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 本地类存储（Local / LocalMount）落盘加密支持
 * <p>
 * 设计目标：<b>性能优先</b>，加密强度满足「防止文件被直接从硬盘拷走即可读」：
 * <ul>
 *   <li>AES-128-CTR：流式、无填充、加解密同一条路径，密文长度 = 明文长度（不膨胀），支持随机访问；</li>
 *   <li>JDK 默认 JCE（带 AES-NI 硬件加速），单线程吞吐通常 1 GB/s 以上，IO 瓶颈远大于加密；</li>
 *   <li>IV = per-file 随机 8 字节 + 文件内偏移对应 CTR 计数器，同 key 不同文件密文不同；</li>
 *   <li>密钥 = SHA-256(配置口令) 截取 16 字节，口令变更后旧文件自然无法解密（表现为不可读），满足「改 key 即全部作废」预期。</li>
 * </ul>
 * 落盘格式：{@code [GFS1(4B)][IV(8B)][密文(nB)]}，头 12 字节明文存放用于嗅探与随机访问定位。
 */
public final class StreamCipherSupport {

    /** 魔数 + 版本：GFS1 */
    static final byte[] MAGIC = {'G', 'F', 'S', '1'};
    /** 头长度 = 魔数 4 + IV 8 */
    public static final int HEADER_LENGTH = MAGIC.length + 8;
    /** AES-128 密钥长度 */
    private static final int KEY_LENGTH = 16;
    /** CTR 计数器块大小 */
    private static final int AES_BLOCK_SIZE = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private StreamCipherSupport() {
    }

    /**
     * 是否为加密文件（按头魔数嗅探，明文文件恰好以 "GFS1" 开头的概率可忽略）
     */
    public static boolean isEncryptedFile(Path file) {
        try (InputStream in = newHeaderInputStream(file)) {
            return Arrays.equals(in.readNBytes(MAGIC.length), MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    private static InputStream newHeaderInputStream(Path file) throws IOException {
        return new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file, java.nio.file.StandardOpenOption.READ), HEADER_LENGTH);
    }

    /**
     * 口令派生 AES-128 密钥：SHA-256 口令后取前 16 字节
     */
    static SecretKeySpec deriveKey(String passphrase) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(passphrase.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(Arrays.copyOf(hash, KEY_LENGTH), "AES");
        } catch (Exception e) {
            throw new StorageOperationException("加密密钥派生失败: " + e.getMessage(), e);
        }
    }

    /**
     * 包装输出流：写入 12 字节头（魔数 + 随机 IV），随后内容 AES-CTR 加密落盘
     */
    public static OutputStream encryptingOutputStream(OutputStream out, String passphrase) throws IOException {
        byte[] ivSeed = new byte[8];
        RANDOM.nextBytes(ivSeed);
        out.write(MAGIC);
        out.write(ivSeed);
        Cipher cipher = openCipher(deriveKey(passphrase), ivSeed, 0, 0L);
        return new CipherFeedbackOutputStream(out, cipher);
    }

    /**
     * 包装输入流：读取头后按 CTR 从明文偏移 0 开始解密
     * （调用方需从文件头读起；Range 场景请用 {@link #newCipher} 自行定位）
     */
    public static InputStream decryptingInputStream(InputStream in, String passphrase) throws IOException {
        byte[] header = in.readNBytes(HEADER_LENGTH);
        if (header.length < HEADER_LENGTH || !startsWithMagic(header)) {
            throw new StorageOperationException("文件已损坏或不是加密文件");
        }
        Cipher cipher = openCipher(deriveKey(passphrase), header, MAGIC.length, 0L);
        return new CipherFeedbackInputStream(in, cipher);
    }

    /**
     * 生成定位到「明文偏移 plaintextStart」的 CTR cipher（Range 读取用）
     *
     * @param fileHeader 文件前 12 字节
     */
    public static Cipher newCipher(String passphrase, byte[] fileHeader, long plaintextStart) {
        if (fileHeader.length < HEADER_LENGTH || !startsWithMagic(fileHeader)) {
            throw new StorageOperationException("文件已损坏或不是加密文件");
        }
        return openCipher(deriveKey(passphrase), fileHeader, MAGIC.length, plaintextStart);
    }

    private static boolean startsWithMagic(byte[] header) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * CTR 模式：IV 前 8 字节取文件随机种子，后 8 字节为从 blockIndex 起的大端计数器
     */
    private static Cipher openCipher(SecretKeySpec key, byte[] header, int ivOffset, long plaintextStart) {
        try {
            long blockIndex = plaintextStart / AES_BLOCK_SIZE;
            ByteBuffer counter = ByteBuffer.allocate(AES_BLOCK_SIZE);
            counter.put(header, ivOffset, 8);
            counter.putLong(blockIndex);
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(counter.array()));
            int skip = (int) (plaintextStart % AES_BLOCK_SIZE);
            if (skip > 0) {
                // 构造 skip 个零字节走一遍 update 推进 keystream，使输出对齐请求偏移
                cipher.update(new byte[skip]);
            }
            return cipher;
        } catch (Exception e) {
            throw new StorageOperationException("加密器初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加密输出流：update 段写回底层流；CTR 无 padding 不调用 doFinal（避免多出块尾处理）
     */
    private static class CipherFeedbackOutputStream extends FilterOutputStream {
        private final Cipher cipher;

        CipherFeedbackOutputStream(OutputStream out, Cipher cipher) {
            super(out);
            this.cipher = cipher;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            byte[] enc = cipher.update(b, off, len);
            if (enc != null && enc.length > 0) {
                out.write(enc);
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            try {
                byte[] tail = cipher.doFinal();
                if (tail != null && tail.length > 0) {
                    out.write(tail);
                }
            } catch (Exception e) {
                throw new IOException("加密收尾失败", e);
            } finally {
                out.close();
            }
        }
    }

    /**
     * 解密输入流
     */
    private static class CipherFeedbackInputStream extends FilterInputStream {
        private final Cipher cipher;

        CipherFeedbackInputStream(InputStream in, Cipher cipher) {
            super(in);
            this.cipher = cipher;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n <= 0) {
                return n;
            }
            byte[] dec = cipher.update(b, off, n);
            if (dec != null && dec != b) {
                System.arraycopy(dec, 0, b, off, dec.length);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = 0;
            byte[] buf = new byte[8192];
            while (skipped < n) {
                int r = read(buf, 0, (int) Math.min(buf.length, n - skipped));
                if (r == -1) break;
                skipped += r;
            }
            return skipped;
        }
    }
}
