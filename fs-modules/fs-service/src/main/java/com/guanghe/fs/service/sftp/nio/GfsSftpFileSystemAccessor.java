package com.guanghe.fs.service.sftp.nio;

import org.apache.sshd.common.util.io.FileInfoExtractor;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * GFS 专用 SftpFileSystemAccessor：
 * sshd 默认 accessor 在 basic 视图缺少 owner/group/permissions 时会回退
 * IoUtils.getPermissions → GfsPath.toFile()（抛 UnsupportedOperationException，READDIR 整体失败）。
 * 这里改为直接从 GfsPath 自身的 basic 层补齐全部 unix 视图属性，不触碰 java.io.File。
 */
public class GfsSftpFileSystemAccessor implements SftpFileSystemAccessor {

    /** 固定 owner/group（GFS 无 UNIX 账号体系，登录用户即 owner） */
    private static final String OWNER = "gfs";
    private static final String GROUP = "gfs";

    /** rwx 权限：目录 755、文件 644 */
    private static final Set<PosixFilePermission> DIR_PERMS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);

    @Override
    public NavigableMap<String, Object> resolveReportedFileAttributes(
            SftpSubsystemProxy subsystem, Path file, int flags,
            NavigableMap<String, Object> attributes, LinkOption... options) throws IOException {
        NavigableMap<String, Object> result = (attributes == null)
                ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
                : new TreeMap<>(attributes);
        if (!(file instanceof GfsPath gp)) {
            return SftpFileSystemAccessor.super.resolveReportedFileAttributes(
                    subsystem, file, flags, attributes, options);
        }

        GfsBasicFileAttributes attrs = readBasic(gp);
        result.putIfAbsent("size", attrs.size());
        result.putIfAbsent("lastModifiedTime", attrs.lastModifiedTime());
        result.put("isDirectory", attrs.isDirectory());
        result.put("isRegularFile", attrs.isRegularFile());
        result.put("isSymbolicLink", false);
        result.put("isOther", false);
        result.put("permissions", attrs.isDirectory() ? DIR_PERMS : FILE_PERMS);
        result.put("owner", OWNER);
        result.put("group", GROUP);
        return result;
    }

    @Override
    public UserPrincipal resolveFileOwner(SftpSubsystemProxy subsystem, Path file, UserPrincipal principal) {
        return new GfsUserPrincipal(OWNER);
    }

    @Override
    public GroupPrincipal resolveGroupOwner(SftpSubsystemProxy subsystem, Path file, GroupPrincipal group) {
        return new GfsGroupPrincipal(GROUP);
    }

    @Override
    public Map<String, ?> readFileAttributes(
            SftpSubsystemProxy subsystem, Path file, String viewType, LinkOption... options) throws IOException {
        // "unix:*" / "basic:*" 统一走 basic 层 + 补齐权限
        GfsBasicFileAttributes attrs = readBasic((GfsPath) file);
        Map<String, Object> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.put("size", attrs.size());
        map.put("lastModifiedTime", attrs.lastModifiedTime());
        map.put("isDirectory", attrs.isDirectory());
        map.put("isRegularFile", attrs.isRegularFile());
        map.put("isSymbolicLink", false);
        map.put("isOther", false);
        map.put("permissions", attrs.isDirectory() ? DIR_PERMS : FILE_PERMS);
        map.put("owner", OWNER);
        map.put("group", GROUP);
        return map;
    }

    /**
     * 覆盖默认 openFile：默认实现走 FileChannel.open →
     * FileSystemProvider.newFileChannel（nio SPI 未实现 → SSH_FX_OP_UNSUPPORTED）。
     * 这里改为 Files.newByteChannel，由 GfsFileSystemProvider 路由到
     * GfsReadChannel / GfsWriteChannel。MINA 内部把 SeekableByteChannel 包进
     * FileChannel-like 通道使用（tryLock/sync 仅 v6 扩展才会触达）。
     */
    @Override
    public SeekableByteChannel openFile(
            SftpSubsystemProxy subsystem, FileHandle fileHandle, Path file, String handle,
            Set<? extends java.nio.file.OpenOption> options, java.nio.file.attribute.FileAttribute<?>... attrs)
            throws IOException {
        if (!(file instanceof GfsPath)) {
            return SftpFileSystemAccessor.super.openFile(subsystem, fileHandle, file, handle, options, attrs);
        }
        Set<java.nio.file.OpenOption> effective = new java.util.HashSet<>(options);
        // TRUNCATE_EXISTING 需要 CREATE 配合（openssh put 新文件只发 CREAT|WRITE|TRUNC）
        if (effective.contains(StandardOpenOption.TRUNCATE_EXISTING)
                && !effective.contains(StandardOpenOption.WRITE)
                && !effective.contains(StandardOpenOption.CREATE)) {
            effective.add(StandardOpenOption.WRITE);
            effective.add(StandardOpenOption.CREATE);
        }
        return Files.newByteChannel(file, effective, attrs);
    }

    private GfsBasicFileAttributes readBasic(GfsPath gp) throws IOException {
        if (gp.isRoot()) {
            return new GfsBasicFileAttributes(null, 0L, LocalDateTime.now(), true, gp.toString());
        }
        com.guanghe.fs.file.domain.FileInfo info = gp.toFileInfo();
        if (info == null) {
            throw new java.nio.file.NoSuchFileException(gp.toString());
        }
        return new GfsBasicFileAttributes(info.getId(), info.getSize(), info.getUploadTime(),
                Boolean.TRUE.equals(info.getIsDir()), gp.toString());
    }

    /** 供 FILEATTRS_RESOLVERS 风格调用使用（保留以显式表达权限语义） */
    @SuppressWarnings("unused")
    private static Set<PosixFilePermission> permsOf(boolean directory) {
        return directory ? DIR_PERMS : FILE_PERMS;
    }

    @SuppressWarnings("unused")
    private static FileTime now() {
        return FileTime.from(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant());
    }

    /** 显式引用，防止 import 被误删（extractor 体系未来接入时使用） */
    @SuppressWarnings("unused")
    private static final FileInfoExtractor<Boolean> EXISTS = FileInfoExtractor.EXISTS;

    record GfsUserPrincipal(String name) implements UserPrincipal {
        @Override
        public String getName() {
            return name;
        }
    }

    record GfsGroupPrincipal(String name) implements GroupPrincipal {
        @Override
        public String getName() {
            return name;
        }
    }
}
