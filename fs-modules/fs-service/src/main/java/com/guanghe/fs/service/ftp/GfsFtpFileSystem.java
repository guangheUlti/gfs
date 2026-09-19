package com.guanghe.fs.service.ftp;

import com.guanghe.fs.file.domain.FileInfo;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpFile;

/**
 * 每个 FTP 会话一个视图：工作目录语义 + 路径解析（相对路径按 CWD 展开）。
 * 所有 FtpFile 实例由 {@link GfsFtpBridge} 产出，落库/读流均走 GFS。
 */
public class GfsFtpFileSystem implements FileSystemView {

    private final GfsFtpBridge bridge;
    private final String userId;

    /** 当前工作目录（绝对路径，规范形如 /a/b；根为 "" 的 "/":统一用 "/" 前缀形） */
    private String cwd = "/";

    GfsFtpFileSystem(GfsFtpBridge bridge, String userId) {
        this.bridge = bridge;
        this.userId = userId;
    }

    private String abs(String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        int semi = p.indexOf(';');
        if (semi >= 0) {
            p = p.substring(0, semi);
        }
        String base;
        if (p.startsWith("/")) {
            base = p;
        } else {
            base = ("/".equals(cwd) || cwd.isEmpty()) ? "/" + p : cwd + "/" + p;
        }
        // 折叠 "." 与 ".."
        String norm = com.guanghe.fs.service.sftp.nio.GfsPathResolver.normalize(base);
        return "/" + norm;
    }

    @Override
    public FtpFile getHomeDirectory() {
        return new GfsFtpFile(bridge, userId, "/", null);
    }

    @Override
    public FtpFile getWorkingDirectory() {
        return new GfsFtpFile(bridge, userId, cwd, null);
    }

    @Override
    public boolean changeWorkingDirectory(String dir) {
        String target = abs(dir);
        // 仅允许切换到真实存在的目录
        boolean isDir = target.equals("/") || bridge.isDirectory(userId, target);
        if (isDir) {
            cwd = target;
            return true;
        }
        return false;
    }

    @Override
    public FtpFile getFile(String path) {
        String target = abs(path);
        // 缓存已存在记录（STOR/RETR 少一次解析）；不存在则 cached=null，FtpFile 内按需再查
        FileInfo cached = target.equals("/") ? null : bridge.resolve(userId, target);
        return new GfsFtpFile(bridge, userId, target, cached);
    }

    @Override
    public boolean isRandomAccessible() {
        return true; // 支持断点续传读（REST），createInputStream(offset) 已实现
    }

    @Override
    public void dispose() {
        // 无资源需释放
    }
}
