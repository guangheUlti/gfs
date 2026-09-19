package com.guanghe.fs.service.ftp;

import com.guanghe.fs.file.domain.FileInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.usermanager.impl.WriteRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * GFS 虚拟文件：FtpServer 的 FtpFile SPI 落在 {@link GfsFtpBridge} 上。
 * 每个实例持有「会话 userId + 绝对路径」，元数据按需查库（FtpServer LIST 每项都会调）。
 */
@Slf4j
public class GfsFtpFile implements FtpFile {

    private final GfsFtpBridge bridge;
    private final String userId;
    private final String absolutePath;
    private final FileInfo cached;

    GfsFtpFile(GfsFtpBridge bridge, String userId, String absolutePath, FileInfo cached) {
        this.bridge = bridge;
        this.userId = userId;
        this.absolutePath = absolutePath;
        this.cached = cached;
    }

    static GfsFtpFile of(GfsFtpBridge bridge, String userId, String absolutePath) {
        String norm = GfsPathResolverNormalize.toNorm(absolutePath);
        FileInfo file = norm.isEmpty() ? null : bridge.resolve(userId, absolutePath);
        return new GfsFtpFile(bridge, userId, "/" + norm, file);
    }

    private String norm() {
        return GfsPathResolverNormalize.toNorm(absolutePath);
    }

    @Override
    public String getAbsolutePath() {
        return "/" + norm();
    }

    @Override
    public boolean isHidden() {
        return getName().startsWith(".");
    }

    @Override
    public String getOwnerName() {
        return userId;
    }

    @Override
    public String getGroupName() {
        return "gfs";
    }

    @Override
    public int getLinkCount() {
        return isDirectory() ? 3 : 1;
    }

    @Override
    public Object getPhysicalFile() {
        return cached;
    }

    @Override
    public String getName() {
        String n = norm();
        if (n.isEmpty()) {
            return "/";
        }
        int idx = n.lastIndexOf('/');
        return idx < 0 ? n : n.substring(idx + 1);
    }

    @Override
    public boolean isDirectory() {
        return norm().isEmpty() || bridge.isDirectory(userId, absolutePath);
    }

    @Override
    public boolean isFile() {
        return !isDirectory() && bridge.isFile(userId, absolutePath);
    }

    @Override
    public boolean doesExist() {
        if (norm().isEmpty()) {
            return true;
        }
        return bridge.isDirectory(userId, absolutePath) || bridge.isFile(userId, absolutePath);
    }

    @Override
    public long getSize() {
        return isDirectory() ? 0L : bridge.fileSize(userId, absolutePath);
    }

    @Override
    public long getLastModified() {
        return bridge.lastModified(userId, absolutePath);
    }

    @Override
    public boolean setLastModified(long time) {
        return false; // 不支持：GFS 无 mtime 写通道
    }

    @Override
    public boolean isReadable() {
        return doesExist();
    }

    @Override
    public boolean isWritable() {
        return true; // 权限由 GFS 文件层语义约束（全量放行）
    }

    @Override
    public boolean isRemovable() {
        return !norm().isEmpty() && doesExist();
    }

    /**
     * 父目录（FtpServer 1.2.x 的 FtpFile 接口无此方法，内部工具方法）
     */
    public GfsFtpFile parentFile() {
        String n = norm();
        int idx = n.lastIndexOf('/');
        String parentPath = idx <= 0 ? "/" : "/" + n.substring(0, idx);
        return new GfsFtpFile(bridge, userId, parentPath, null);
    }

    /**
     * 子项（内部工具方法）
     */
    public GfsFtpFile childFile(String name) {
        String n = norm();
        String child = n.isEmpty() ? "/" + name : "/" + n + "/" + name;
        return new GfsFtpFile(bridge, userId, child, null);
    }

    @Override
    public List<? extends FtpFile> listFiles() {
        if (!isDirectory()) {
            return List.of();
        }
        return bridge.listChildren(userId, absolutePath).stream()
                .map(info -> new GfsFtpFile(bridge, userId,
                        ("/" + norm() + (norm().isEmpty() ? "" : "/") + info.getDisplayName()), info))
                .toList();
    }

    @Override
    public boolean mkdir() {
        return bridge.mkdir(userId, absolutePath);
    }

    @Override
    public boolean delete() {
        return bridge.delete(userId, absolutePath);
    }

    @Override
    public boolean move(FtpFile target) {
        // FTP 客户端 Rename = 同目录改名；跨目录走 move
        if (target instanceof GfsFtpFile gfsTarget) {
            String from = norm();
            String to = gfsTarget.norm();
            String fromParent = from.contains("/") ? from.substring(0, from.lastIndexOf('/')) : "";
            String toParent = to.contains("/") ? to.substring(0, to.lastIndexOf('/')) : "";
            if (fromParent.equals(toParent)) {
                return bridge.rename(userId, absolutePath, target.getName());
            }
            return bridge.move(userId, absolutePath, gfsTarget.absolutePath);
        }
        return false;
    }

    @Override
    public InputStream createInputStream(long offset) throws IOException {
        long size = getSize();
        if (offset > 0) {
            if (size < 0 || offset >= size) {
                throw new IOException("offset beyond EOF: " + offset + " >= " + size);
            }
            InputStream in = bridge.readFileRange(userId, absolutePath, offset, Math.max(size - 1, offset));
            if (in == null) {
                throw new IOException("cannot open range stream");
            }
            return in;
        }
        InputStream in = bridge.readFile(userId, absolutePath);
        if (in == null) {
            throw new IOException("cannot open stream");
        }
        return in;
    }

    @Override
    public OutputStream createOutputStream(long offset) throws IOException {
        if (offset > 0) {
            // 不支持断点续传写（REST）：GFS 写通道是 spool-全量提交语义
            throw new IOException("resume upload not supported (offset=" + offset + ")");
        }
        return bridge.writeFile(userId, absolutePath);
    }
}

/**
 * 归一化工具聚合（避免 bridge 与 file 相互依赖）
 */
final class GfsPathResolverNormalize {

    private GfsPathResolverNormalize() {
    }

    static String toNorm(String path) {
        return GfsFtpBridgeNormalizeHolder.normalize(path);
    }
}

/**
 * 持有归一化逻辑：委托 GfsPathResolver.normalize（静态工具）
 */
final class GfsFtpBridgeNormalizeHolder {

    private GfsFtpBridgeNormalizeHolder() {
    }

    static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String p = path;
        // Windows 风格反斜杠容错
        p = p.replace('\\', '/');
        // 去掉查询串残留
        int semi = p.indexOf(';');
        if (semi >= 0) {
            p = p.substring(0, semi);
        }
        return com.guanghe.fs.service.sftp.nio.GfsPathResolver.normalize(p);
    }
}
