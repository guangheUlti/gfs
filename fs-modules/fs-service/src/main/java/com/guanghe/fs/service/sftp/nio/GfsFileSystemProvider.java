package com.guanghe.fs.service.sftp.nio;

import cn.hutool.core.io.IoUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.service.FileInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GFS java.nio 文件系统 Provider：让官方 SftpSubsystem 全量可用。
 * 每个 SFTP 会话一个 GfsFileSystem（MINA 每会话调用一次 newFileSystem）。
 */
@Slf4j
public class GfsFileSystemProvider extends FileSystemProvider {

    public static final String SCHEME = "gfs";

    /** 每 SFTP 会话一个 FileSystem（key = session 由 GfsFileSystemFactory 传入的标记） */
    private final Map<Object, GfsFileSystem> openFileSystems = new ConcurrentHashMap<>();

    private final ObjectProvider<FileInfoService> fileInfoServiceProvider;
    private final com.guanghe.fs.service.sftp.nio.GfsPathResolver pathResolver;
    private final com.guanghe.fs.service.sftp.SaTokenBridge saTokenBridge;

    public GfsFileSystemProvider(ObjectProvider<FileInfoService> fileInfoServiceProvider,
                                 GfsPathResolver pathResolver,
                                 com.guanghe.fs.service.sftp.SaTokenBridge saTokenBridge) {
        super();
        this.fileInfoServiceProvider = fileInfoServiceProvider;
        this.pathResolver = pathResolver;
        this.saTokenBridge = saTokenBridge;
    }

    ObjectProvider<FileInfoService> files() {
        return fileInfoServiceProvider;
    }

    GfsPathResolver resolver() {
        return pathResolver;
    }

    com.guanghe.fs.service.sftp.SaTokenBridge bridge() {
        return saTokenBridge;
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        String userId = env.get("userId") == null ? "anonymous" : env.get("userId").toString();
        GfsFileSystem fs = new GfsFileSystem(this, userId);
        openFileSystems.put(uri, fs);
        return fs;
    }

    /** 注册由 SftpServerHolder 显式创建的文件系统（带会话标记） */
    GfsFileSystem registerFileSystem(Object sessionKey, String userId) {
        GfsFileSystem fs = new GfsFileSystem(this, userId);
        openFileSystems.put(sessionKey, fs);
        return fs;
    }

    void unregisterFileSystem(Object sessionKey) {
        openFileSystems.remove(sessionKey);
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        FileSystem fs = openFileSystems.get(uri);
        if (fs == null) {
            throw new FileSystemNotFoundException(uri.toString());
        }
        return fs;
    }

    @Override
    public Path getPath(URI uri) {
        return getFileSystem(uri).getPath(uri.getPath() == null ? "/" : uri.getPath());
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        if (!(path instanceof GfsPath gp)) {
            throw new FileSystemException(path.toString(), null, "not a gfs path");
        }
        boolean write = options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.CREATE)
                || options.contains(StandardOpenOption.CREATE_NEW);
        boolean append = options.contains(StandardOpenOption.APPEND);
        if (write || append) {
            return new GfsWriteChannel(gp, append);
        }
        return new GfsReadChannel(gp);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir,
                                                    DirectoryStream.Filter<? super Path> filter) throws IOException {
        if (!(dir instanceof GfsPath gp)) {
            throw new NotDirectoryException(dir.toString());
        }
        return new GfsDirectoryStream(gp, filter);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        if (!(dir instanceof GfsPath gp)) {
            throw new IOException("not a gfs path");
        }
        com.guanghe.fs.service.sftp.SaTokenBridge bridge = bridge();
        bridge.runAs(gp.getFileSystem().userId(), () -> {
            FileInfo parent = gp.getParent() == null ? null
                    : resolver().resolve(gp.getParent().toString());
            com.guanghe.fs.file.domain.dto.CreateDirectoryCmd cmd =
                    new com.guanghe.fs.file.domain.dto.CreateDirectoryCmd();
            cmd.setParentId(parent == null ? null : parent.getId());
            cmd.setFolderName(GfsPathResolver.baseName(gp.toString()));
            try {
                files().getObject().createDirectory(cmd);
            } catch (RuntimeException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    @Override
    public void delete(Path path) throws IOException {
        if (!(path instanceof GfsPath gp)) {
            throw new IOException("not a gfs path");
        }
        bridge().runAs(gp.getFileSystem().userId(), () -> {
            FileInfo file = gp.toFileInfo();
            if (file == null) {
                throw new RuntimeException(new NoSuchFileException(gp.toString()));
            }
            // 删除 = 进回收站（与 Web 端语义一致，可恢复）
            try {
                files().getObject().moveFilesToRecycleBin(List.of(file.getId()));
            } catch (RuntimeException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        if (!(source instanceof GfsPath src) || !(target instanceof GfsPath dst)) {
            throw new IOException("not a gfs path");
        }
        bridge().runAs(src.getFileSystem().userId(), () -> {
            FileInfo file = src.toFileInfo();
            if (file == null) {
                throw new RuntimeException(new NoSuchFileException(src.toString()));
            }
            FileInfo dstParent = dst.getParent() == null ? null
                    : resolver().resolve(dst.getParent().toString());
            String targetName = GfsPathResolver.baseName(dst.toString());
            boolean sameDir = Objects.equals(file.getParentId(), dstParent == null ? null : dstParent.getId());
            try {
                if (sameDir) {
                    com.guanghe.fs.file.domain.dto.RenameFileCmd cmd =
                            new com.guanghe.fs.file.domain.dto.RenameFileCmd();
                    cmd.setDisplayName(targetName);
                    files().getObject().renameFile(file.getId(), cmd);
                } else {
                    com.guanghe.fs.file.domain.dto.MoveFileCmd cmd =
                            new com.guanghe.fs.file.domain.dto.MoveFileCmd();
                    cmd.setFileIds(List.of(file.getId()));
                    cmd.setDirId(dstParent == null ? null : dstParent.getId());
                    files().getObject().moveFile(cmd);
                    if (!targetName.equals(file.getDisplayName())) {
                        com.guanghe.fs.file.domain.dto.RenameFileCmd rename =
                                new com.guanghe.fs.file.domain.dto.RenameFileCmd();
                        rename.setDisplayName(targetName);
                        files().getObject().renameFile(file.getId(), rename);
                    }
                }
            } catch (RuntimeException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                                                            LinkOption... options) throws IOException {
        // java.nio.Files / MINA SftpSubsystem 传的是 BasicFileAttributes.class 接口，
        // 必须同时接受接口与具体类，否则 opendir/stat 全部 SSH_FX_FAILURE
        if (type != GfsBasicFileAttributes.class && type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException("unsupported attributes type: " + type);
        }
        if (!(path instanceof GfsPath gp)) {
            throw new IOException("not a gfs path");
        }
        if (gp.isRoot()) {
            @SuppressWarnings("unchecked")
            A root = (A) new GfsBasicFileAttributes(null, 0L, null, true, gp.toString());
            return root;
        }
        bridge().runAs(gp.getFileSystem().userId(), () -> {
        });
        FileInfo file = resolveInSession(gp);
        if (file == null) {
            throw new NoSuchFileException(gp.toString());
        }
        @SuppressWarnings("unchecked")
        A attrs = (A) new GfsBasicFileAttributes(file.getId(), file.getSize(), file.getUploadTime(),
                Boolean.TRUE.equals(file.getIsDir()), gp.toString());
        return attrs;
    }

    private FileInfo resolveInSession(GfsPath gp) {
        return gp.toFileInfo();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        if (!(path instanceof GfsPath gp)) {
            throw new IOException("not a gfs path");
        }
        FileInfo file = gp.toFileInfo();
        boolean exists = gp.isRoot() || file != null;
        if (!exists) {
            throw new NoSuchFileException(gp.toString());
        }
        for (AccessMode mode : modes) {
            if (mode == AccessMode.EXECUTE) {
                throw new AccessDeniedException(gp.toString());
            }
        }
    }

    @Override
    public FileStore getFileStore(Path path) {
        return new GfsFileStore();
    }

    // ---------- 以下能力未实现，明确抛错（不做静默降级） ----------

    @Override
    public boolean isSameFile(Path path, Path path2) {
        return path.equals(path2);
    }

    @Override
    public boolean isHidden(Path path) {
        return false;
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException("symbolic links not supported");
    }

    @Override
    public void createLink(Path link, Path existing) {
        throw new UnsupportedOperationException("hard links not supported");
    }

    @Override
    public Path readSymbolicLink(Path link) {
        throw new UnsupportedOperationException("symbolic links not supported");
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        throw new UnsupportedOperationException("setAttribute not supported");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        // NIO 规范：basic 视图必须返回实现；返回 null 会让调用方 NPE / 判定不支持
        if (type == BasicFileAttributeView.class && path instanceof GfsPath gp) {
            return (V) new GfsBasicFileAttributeView(gp);
        }
        return null;
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attribute, LinkOption... options) {
        // sshd SftpFileSystemAccessor 用字符串形式读 "basic:*"，必须支持
        String view = attribute;
        String attrs = "*";
        int idx = attribute.indexOf(':');
        if (idx >= 0) {
            view = attribute.substring(0, idx);
            attrs = attribute.substring(idx + 1);
        }
        if (!"basic".equals(view) && !"*".equals(view)) {
            throw new UnsupportedOperationException("readAttributes(String) unsupported view: " + attribute);
        }
        try {
            GfsBasicFileAttributes a = readAttributes(path, GfsBasicFileAttributes.class, options);
            Map<String, Object> map = new LinkedHashMap<>();
            boolean all = "*".equals(attrs) || attrs.isBlank();
            java.util.Set<String> names = all
                    ? java.util.Set.of("lastModifiedTime", "lastAccessTime", "creationTime", "isRegularFile",
                            "isDirectory", "isSymbolicLink", "isOther", "size", "fileKey")
                    : java.util.Set.of(attrs.split(","));
            for (String n : names) {
                switch (n) {
                    case "lastModifiedTime" -> map.put(n, a.lastModifiedTime());
                    case "lastAccessTime" -> map.put(n, a.lastAccessTime());
                    case "creationTime" -> map.put(n, a.creationTime());
                    case "isRegularFile" -> map.put(n, a.isRegularFile());
                    case "isDirectory" -> map.put(n, a.isDirectory());
                    case "isSymbolicLink" -> map.put(n, a.isSymbolicLink());
                    case "isOther" -> map.put(n, a.isOther());
                    case "size" -> map.put(n, a.size());
                    case "fileKey" -> map.put(n, a.fileKey());
                    default -> { /* 未知属性忽略（Files 规范） */ }
                }
            }
            return map;
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        if (!(source instanceof GfsPath src) || !(target instanceof GfsPath dst)) {
            throw new IOException("not a gfs path");
        }
        bridge().runAs(src.getFileSystem().userId(), () -> {
            FileInfo file = src.toFileInfo();
            if (file == null) {
                throw new RuntimeException(new NoSuchFileException(src.toString()));
            }
            FileInfo dstParent = dst.getParent() == null ? null
                    : resolver().resolve(dst.getParent().toString());
            com.guanghe.fs.file.domain.dto.CopyFileCmd cmd = new com.guanghe.fs.file.domain.dto.CopyFileCmd();
            cmd.setFileIds(List.of(file.getId()));
            cmd.setDirId(dstParent == null ? null : dstParent.getId());
            try {
                files().getObject().copyFiles(cmd);
            } catch (RuntimeException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /** GFS 文件存储（容量信息不精确，仅占位实现） */
    static final class GfsFileStore extends FileStore {
        @Override
        public String name() {
            return "gfs";
        }

        @Override
        public String type() {
            return "gfs";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public long getTotalSpace() {
            return Long.MAX_VALUE;
        }

        @Override
        public long getUsableSpace() {
            return Long.MAX_VALUE;
        }

        @Override
        public long getUnallocatedSpace() {
            return Long.MAX_VALUE;
        }

        @Override
        public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
            return false;
        }

        @Override
        public boolean supportsFileAttributeView(String name) {
            return false;
        }

        @Override
        public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getAttribute(String attribute) {
            throw new UnsupportedOperationException();
        }
    }
}
