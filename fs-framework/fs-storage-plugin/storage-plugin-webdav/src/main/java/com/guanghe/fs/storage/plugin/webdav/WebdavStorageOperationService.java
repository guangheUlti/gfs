package com.guanghe.fs.storage.plugin.webdav;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;
import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.chunk.AbstractTempChunkStorageService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;
import com.guanghe.fs.storage.plugin.webdav.config.WebdavConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebDAV 存储插件（基于 sardine）
 * <p>
 * 纯对象式存储（同 Local/Minio 模式）：分片先落本地 temp，complete 时合并写远程。
 * Sardine 实例线程安全，可复用单实例。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@StoragePlugin(
        identifier = "WebDAV",
        name = "WebDAV",
        description = "通过 WebDAV 协议接入坚果云、Alist、Nextcloud 等网盘与自建服务，跨平台通用性强。",
        icon = "icon-bendicunchu1",
        schemaResource = "classpath:schema/webdav-schema.json"
)
public class WebdavStorageOperationService extends AbstractTempChunkStorageService {

    private Sardine sardine;
    private String baseUrl;

    public WebdavStorageOperationService() {
        super();
    }

    public WebdavStorageOperationService(StorageConfig config) {
        super(config);
    }

    @Override
    protected String getTempRoot() {
        // 分片临时目录固定于运行目录 storage/temp/webdav
        return "storage/temp/webdav";
    }

    @Override
    protected void validateConfig(StorageConfig config) {
        WebdavConfig cfg = readConfig(config);
        if (isBlank(cfg.getWebdavEndpoint())) {
            throw new StorageConfigException("WebDAV 配置错误：服务端点不能为空");
        }
        String endpoint = cfg.getWebdavEndpoint().trim();
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new StorageConfigException("WebDAV 配置错误：服务端点必须以 http:// 或 https:// 开头");
        }
    }

    @Override
    protected void initialize(StorageConfig config) {
        // 真实建连（P3 连接测试的落点）：构建客户端并对端点发起 PROPFIND
        WebdavConfig cfg = readConfig(config);
        String endpoint = trimSlashes(cfg.getWebdavEndpoint());
        String basePath = trimSlashes(cfg.getWebdavBasePath());
        this.baseUrl = endpoint + (basePath.isEmpty() ? "" : "/" + basePath);

        this.sardine = cfg.getWebdavUsername() == null || cfg.getWebdavUsername().isBlank()
                ? SardineFactory.begin()
                : SardineFactory.begin(cfg.getWebdavUsername().trim(), cfg.getWebdavPassword() == null ? "" : cfg.getWebdavPassword());
        try {
            // PROPFIND 根路径，深度 0 仅验证连通性与凭据
            sardine.list(url(""), 0);
            log.info("{} WebDAV 连接测试通过: {}", getLogPrefix(), baseUrl);
        } catch (Exception e) {
            shutdownQuietly();
            throw new StorageConfigException("WebDAV 连接失败: " + rootMessage(e));
        }
    }

    /** 拼接对象完整 URL */
    private String url(String objectKey) {
        String key = normalizeKey(objectKey);
        return baseUrl + (key.isEmpty() ? "" : "/" + encodeSegments(key));
    }

    /** 对路径逐段做 URL 编码（保留 '/'） */
    private String encodeSegments(String key) {
        StringBuilder sb = new StringBuilder();
        for (String segment : key.split("/")) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            // URLEncoder 是表单编码，空格会变 +，需还原为 %20
            sb.append(java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    @Override
    public void uploadFile(InputStream inputStream, String objectKey) {
        ensureNotPrototype();
        try {
            sardine.put(url(objectKey), inputStream);
            log.debug("{} 文件上传成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (Exception e) {
            log.error("{} 文件上传失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("WebDAV 文件上传失败: " + rootMessage(e), e);
        }
    }

    @Override
    public InputStream downloadFile(String objectKey) {
        ensureNotPrototype();
        return downloadFileRange(objectKey, 0, Long.MAX_VALUE - 1);
    }

    @Override
    public InputStream downloadFileRange(String objectKey, long startByte, long endByte) {
        ensureNotPrototype();
        if (startByte < 0 || endByte < startByte) {
            throw new StorageOperationException("无效的字节范围: startByte=" + startByte + ", endByte=" + endByte);
        }
        try {
            long length = endByte - startByte + 1;
            Map<String, String> headers = new HashMap<>();
            headers.put("Range", "bytes=" + startByte + "-" + endByte);
            // begin() 返回的响应流关闭时会释放底层连接
            InputStream raw = sardine.get(url(objectKey), headers);
            return new BoundedInputStream(raw, length);
        } catch (StorageOperationException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} Range读取文件失败: objectKey={}, start={}, end={}",
                    getLogPrefix(), objectKey, startByte, endByte, e);
            throw new StorageOperationException("WebDAV 读取文件失败: " + rootMessage(e), e);
        }
    }

    @Override
    public void deleteFile(String objectKey) {
        ensureNotPrototype();
        try {
            String target = url(objectKey);
            if (sardine.exists(target)) {
                sardine.delete(target);
            } else {
                log.debug("{} 文件不存在，视为删除成功: objectKey={}", getLogPrefix(), objectKey);
            }
        } catch (Exception e) {
            log.error("{} 文件删除失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("WebDAV 文件删除失败: " + rootMessage(e), e);
        }
    }

    @Override
    public void rename(String objectKey, String destObjectKey) {
        ensureNotPrototype();
        try {
            // move 同服务端内改路径（文件/目录通吃）；目标父目录需已存在
            sardine.move(url(objectKey), url(destObjectKey));
            log.debug("{} 重命名成功: {} -> {}", getLogPrefix(), objectKey, destObjectKey);
        } catch (Exception e) {
            log.error("{} 重命名失败: {} -> {}", getLogPrefix(), objectKey, destObjectKey, e);
            throw new StorageOperationException("WebDAV 重命名失败: " + rootMessage(e), e);
        }
    }

    @Override
    public String getFileUrl(String objectKey, Integer expireSeconds) {
        throw new StorageOperationException("WebDAV 存储不支持生成公网直链");
    }

    @Override
    public InputStream getFileStream(String objectKey) {
        return downloadFile(objectKey);
    }

    @Override
    public boolean isFileExist(String objectKey) {
        ensureNotPrototype();
        try {
            return sardine.exists(url(objectKey));
        } catch (Exception e) {
            log.error("{} 检查文件存在失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("WebDAV 检查文件存在失败: " + rootMessage(e), e);
        }
    }

    @Override
    public void mkdirDirectory(String dirKey) {
        ensureNotPrototype();
        try {
            String key = normalizeKey(dirKey);
            if (key.isEmpty()) {
                return;
            }
            // 逐级 mkcol（对已存在目录返回 405，判存跳过）
            StringBuilder current = new StringBuilder();
            for (String segment : key.split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                if (current.length() > 0) {
                    current.append('/');
                }
                current.append(segment);
                String dirUrl = url(current.toString());
                if (!sardine.exists(dirUrl)) {
                    try {
                        sardine.createDirectory(dirUrl);
                    } catch (Exception e) {
                        // 并发下目录可能已被其它请求创建
                        if (!sardine.exists(dirUrl)) {
                            throw e;
                        }
                    }
                }
            }
        } catch (StorageOperationException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} 创建目录失败: dirKey={}", getLogPrefix(), dirKey, e);
            throw new StorageOperationException("WebDAV 创建目录失败: " + rootMessage(e), e);
        }
    }

    @Override
    public List<StorageObjectEntry> listObjects(String dirKey) {
        ensureNotPrototype();
        try {
            String key = normalizeKey(dirKey);
            List<StorageObjectEntry> entries = new ArrayList<>();
            // list(url, 1) 取一层；第一个元素是目录自身，跳过
            List<DavResource> resources = sardine.list(url(key), 1);
            for (DavResource resource : resources) {
                String href = resource.getPath();
                if (href == null) {
                    continue;
                }
                String decoded = decode(href);
                String childKey = trimSlashes(decoded);
                // 跳过目录自身
                if (childKey.equals(key)) {
                    continue;
                }
                entries.add(new StorageObjectEntry(
                        childKey,
                        Boolean.TRUE.equals(resource.isDirectory()),
                        resource.isDirectory() ? null : resource.getContentLength(),
                        resource.getModified() == null ? null : resource.getModified().getTime()
                ));
            }
            return entries;
        } catch (Exception e) {
            log.error("{} 列举目录失败: dirKey={}", getLogPrefix(), dirKey, e);
            throw new StorageOperationException("WebDAV 列举目录失败: " + rootMessage(e), e);
        }
    }

    @Override
    public void deleteDirectory(String dirKey) {
        ensureNotPrototype();
        try {
            String target = url(dirKey);
            if (sardine.exists(target)) {
                // DELETE 对集合即递归删除
                sardine.delete(target);
            }
        } catch (Exception e) {
            log.error("{} 删除目录失败: dirKey={}", getLogPrefix(), dirKey, e);
            throw new StorageOperationException("WebDAV 删除目录失败: " + rootMessage(e), e);
        }
    }

    @Override
    public void writeMerged(java.nio.file.Path mergedFile, String objectKey) {
        // complete 阶段：将合并后的本地临时文件写入远程
        ensureNotPrototype();
        try (InputStream in = java.nio.file.Files.newInputStream(mergedFile)) {
            sardine.put(url(objectKey), in);
        } catch (Exception e) {
            log.error("{} 合并文件写入远程失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("WebDAV 合并文件写入失败: " + rootMessage(e), e);
        }
    }

    @Override
    public void close() {
        shutdownQuietly();
    }

    private void shutdownQuietly() {
        if (sardine != null) {
            try {
                sardine.shutdown();
            } catch (Exception ignored) {
            }
            sardine = null;
        }
    }

    private WebdavConfig readConfig(StorageConfig config) {
        try {
            return WebdavConfig.toObject(config);
        } catch (Exception e) {
            throw new StorageConfigException("WebDAV 配置解析失败: " + e.getMessage());
        }
    }

    /** 去除首尾 '/' */
    private static String trimSlashes(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** 服务端返回的 href 是 URL 编码路径，还原后再比较 */
    private static String decode(String path) {
        try {
            return java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return path;
        }
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static String normalizeKey(String objectKey) {
        String key = objectKey == null ? "" : objectKey.trim();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    /**
     * 限制读取长度的流包装（Range 响应用），close 时连带关闭底层连接
     */
    private static class BoundedInputStream extends InputStream {
        private final InputStream inner;
        private long remaining;

        BoundedInputStream(InputStream inner, long length) {
            this.inner = new BufferedInputStream(inner, 65536);
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int n = inner.read(b, off, toRead);
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }

        @Override
        public void close() throws IOException {
            try {
                inner.close();
            } catch (Exception ignored) {
            }
        }
    }
}
