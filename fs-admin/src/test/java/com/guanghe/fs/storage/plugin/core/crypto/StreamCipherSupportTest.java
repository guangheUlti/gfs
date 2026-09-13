package com.guanghe.fs.storage.plugin.core.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StreamCipherSupport 回环自验：加密->解密一致、Range 定位一致、密文确实不可读、性能摸底
 */
class StreamCipherSupportTest {

    private static final String KEY = "my-secret-key-2026";

    @Test
    void roundtripSmallAndEmpty() throws Exception {
        checkRoundtrip("hello, 加密世界!".getBytes(StandardCharsets.UTF_8));
        checkRoundtrip(new byte[0]);
        checkRoundtrip(new byte[]{1});
    }

    @Test
    void roundtripLargeRandom() throws Exception {
        byte[] data = new byte[5 * 1024 * 1024 + 137]; // 非 16 倍数
        new Random(42).nextBytes(data);
        checkRoundtrip(data);
    }

    @Test
    void ciphertextIsNotPlaintextAndIvRandomized() throws Exception {
        byte[] data = "top-secret-content-0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] enc1 = encrypt(data);
        byte[] enc2 = encrypt(data);
        // 同 key 同内容两次加密：随机 IV 使密文不同
        assertFalse(Arrays.equals(enc1, enc2));
        // 密文不含明文片段
        String cipherText = new String(Arrays.copyOfRange(enc1, StreamCipherSupport.HEADER_LENGTH, enc1.length), StandardCharsets.ISO_8859_1);
        assertFalse(cipherText.contains("top-secret"));
        // 密文长度 = 明文长度 + 头
        assertEquals(data.length + StreamCipherSupport.HEADER_LENGTH, enc1.length);
    }

    @Test
    void rangeDecryptMatchesPlaintextSlices() throws Exception {
        byte[] data = new byte[1024 * 1024 + 55];
        new Random(7).nextBytes(data);
        Path file = Files.createTempFile("gfs-cipher-test", ".bin");
        try {
            Files.write(file, encrypt(data));
            byte[] header = Arrays.copyOf(Files.readAllBytes(file), StreamCipherSupport.HEADER_LENGTH);

            // 大小两个窗口、非块对齐偏移都要正确
            long[][] ranges = {
                    {0, 1023},
                    {1, 1024},
                    {15, 16},
                    {100_000, 199_999},
                    {data.length - 100, data.length - 1},
                    {data.length - 1, data.length + 999}, // 越界截断
            };
            for (long[] range : ranges) {
                long start = range[0];
                long end = range[1];
                long cipherLength = Files.size(file) - StreamCipherSupport.HEADER_LENGTH;
                long plainStart = Math.min(start, cipherLength);
                long plainEndIncl = Math.min(end, cipherLength - 1);
                if (plainEndIncl < plainStart) {
                    continue;
                }
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    raf.seek(StreamCipherSupport.HEADER_LENGTH + plainStart);
                    int expectLen = (int) (plainEndIncl - plainStart + 1);
                    byte[] cipherBuf = new byte[expectLen];
                    raf.readFully(cipherBuf);
                    Cipher cipher = StreamCipherSupport.newCipher(KEY, header, plainStart);
                    byte[] plain = cipher.update(cipherBuf);
                    assertArrayEquals(
                            Arrays.copyOfRange(data, (int) plainStart, (int) plainEndIncl + 1),
                            plain,
                            "range [" + start + "," + end + "] mismatch");
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void decryptStreamRejectsNonEncryptedInput() {
        byte[] plain = "just-a-plain-file".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> {
            try (InputStream ignored = StreamCipherSupport.decryptingInputStream(
                    new ByteArrayInputStream(plain), KEY)) {
                // no-op
            }
        });
    }

    @Test
    void sniffDetectsEncryptedFile(@TempDir Path dir) throws Exception {
        Path enc = dir.resolve("enc.bin");
        Files.write(enc, encrypt("data".getBytes(StandardCharsets.UTF_8)));
        Path plain = dir.resolve("plain.bin");
        Files.write(plain, "data".getBytes(StandardCharsets.UTF_8));

        assertTrue(StreamCipherSupport.isEncryptedFile(enc));
        assertFalse(StreamCipherSupport.isEncryptedFile(plain));
    }

    @Test
    void throughputSmoke() throws Exception {
        // 64MB 摸底：加密吞吐应远超普通磁盘写入（>200MB/s），否则设计不成立
        byte[] data = new byte[8 * 1024 * 1024];
        new Random(1).nextBytes(data);
        ByteArrayOutputStream encOut = new ByteArrayOutputStream();
        long encStart = System.nanoTime();
        try (java.io.OutputStream out = StreamCipherSupport.encryptingOutputStream(encOut, KEY)) {
            for (int i = 0; i < 8; i++) {
                out.write(data);
            }
        }
        long encCost = System.nanoTime() - encStart;
        byte[] cipher = encOut.toByteArray();

        byte[] decrypted = new byte[cipher.length - StreamCipherSupport.HEADER_LENGTH];
        long decStart = System.nanoTime();
        try (InputStream in = StreamCipherSupport.decryptingInputStream(
                new ByteArrayInputStream(cipher), KEY)) {
            int off = 0;
            while (off < decrypted.length) {
                int n = in.read(decrypted, off, decrypted.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        long decCost = System.nanoTime() - decStart;

        assertEquals(64L * 1024 * 1024, decrypted.length);
        double encMbPerSec = 64.0 / (encCost / 1_000_000_000.0);
        double decMbPerSec = 64.0 / (decCost / 1_000_000_000.0);
        System.out.printf("encrypt throughput: %.0f MB/s, decrypt: %.0f MB/s%n", encMbPerSec, decMbPerSec);
        assertTrue(encMbPerSec > 200, "encrypt too slow: " + encMbPerSec + " MB/s");
        assertTrue(decMbPerSec > 200, "decrypt too slow: " + decMbPerSec + " MB/s");
    }

    private void checkRoundtrip(byte[] data) throws Exception {
        byte[] encrypted = encrypt(data);
        InputStream in = StreamCipherSupport.decryptingInputStream(
                new ByteArrayInputStream(encrypted), KEY);
        byte[] decrypted = in.readAllBytes();
        assertArrayEquals(data, decrypted);
        // 错误密钥解密：不应抛异常（CTR 无认证），但结果必须不是原文
        byte[] wrongKey = StreamCipherSupport.decryptingInputStream(
                new ByteArrayInputStream(encrypted), "other-key").readAllBytes();
        if (data.length > 0) {
            assertNotEquals(Arrays.hashCode(data), Arrays.hashCode(wrongKey));
        }
    }

    private byte[] encrypt(byte[] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (java.io.OutputStream out = StreamCipherSupport.encryptingOutputStream(bos, KEY)) {
            out.write(data);
        }
        return bos.toByteArray();
    }
}
