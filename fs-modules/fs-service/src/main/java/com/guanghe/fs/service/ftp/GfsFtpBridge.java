package com.guanghe.fs.service.ftp;

import cn.hutool.core.io.IoUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.dto.CreateDirectoryCmd;
import com.guanghe.fs.file.domain.dto.RenameFileCmd;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.service.sftp.SaTokenBridge;
import com.guanghe.fs.service.sftp.nio.GfsPathResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.Authentication;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.ftplet.AuthorizationRequest;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * GFS 文件树桥：把 FtpServer 的「虚拟文件系统 + 用户认证」两个 SPI 直接落在 FileInfoService 上。
 * <p>
 * 与 SFTP 的 NIO FileSystem / WebDAV 的 DavController 同源同语义：
 * 逐段路径解析（{@link GfsPathResolver}）、Sa-Token 上下文桥接（{@link SaTokenBridge}）、
 * 回收站/加密/挂载等业务语义全部继承。所有操作都包在 runAs(userId) 内，FTP 线程无需 HTTP 上下文。
 */
@Slf4j
@Component
public class GfsFtpBridge implements UserManager {

    private final ObjectProvider<FileInfoService> fileInfoServiceProvider;
    private final GfsPathResolver pathResolver;
    private final SaTokenBridge saTokenBridge;
    private final com.guanghe.fs.service.webdav.DavUserAuthenticator authenticator;
    private final com.guanghe.fs.system.mapper.SysUserMapper userMapper;

    public GfsFtpBridge(ObjectProvider<FileInfoService> fileInfoServiceProvider,
                        GfsPathResolver pathResolver,
                        SaTokenBridge saTokenBridge,
                        com.guanghe.fs.service.webdav.DavUserAuthenticator authenticator,
                        com.guanghe.fs.system.mapper.SysUserMapper userMapper) {
        this.fileInfoServiceProvider = fileInfoServiceProvider;
        this.pathResolver = pathResolver;
        this.saTokenBridge = saTokenBridge;
        this.authenticator = authenticator;
        this.userMapper = userMapper;
    }

    private FileInfoService files() {
        return fileInfoServiceProvider.getObject();
    }

    // ==================== UserManager：认证 ====================

    @Override
    public User getUserByName(String username) throws FtpException {
        // FtpServer 认证成功后通过 getUserByName 取 User；查库补全
        com.guanghe.fs.system.domain.SysUser sysUser = findSysUser(username);
        if (sysUser == null) {
            return null;
        }
        return new GfsFtpUser(sysUser.getUsername(), sysUser.getId());
    }

    @Override
    public String[] getAllUserNames() {
        return new String[0]; // 管理接口用不到，空实现
    }

    @Override
    public String getAdminName() {
        return "admin"; // GFS 无 FTP 管理员概念，固定返回
    }

    @Override
    public boolean isAdmin(String username) {
        return "admin".equals(username); // 与 GFS 超管同名者视为 FTP 管理员
    }

    @Override
    public boolean doesExist(String username) throws FtpException {
        return findSysUser(username) != null;
    }

    @Override
    public User authenticate(Authentication authentication) throws AuthenticationFailedException {
        if (!(authentication instanceof UsernamePasswordAuthentication passwd)) {
            throw new AuthenticationFailedException("only password auth supported");
        }
        com.guanghe.fs.system.domain.SysUser user =
                authenticator.authenticate(passwd.getUsername(), passwd.getPassword());
        if (user == null) {
            log.info("FTP 认证失败: username={}", passwd.getUsername());
            throw new AuthenticationFailedException("530 Login incorrect");
        }
        return new GfsFtpUser(user.getUsername(), user.getId());
    }

    @Override
    public void save(User user) {
        throw new UnsupportedOperationException("FTP 用户由系统账号管理");
    }

    @Override
    public void delete(String username) {
        throw new UnsupportedOperationException("FTP 用户由系统账号管理");
    }

    private com.guanghe.fs.system.domain.SysUser findSysUser(String username) {
        if (username == null) {
            return null;
        }
        return userMapper.selectOneByQuery(
                com.mybatisflex.core.query.QueryWrapper.create()
                        .where(com.guanghe.fs.system.domain.table.SysUserTableDef.SYS_USER.USERNAME.eq(username)));
    }

    // ==================== 用户模型 ====================

    /** FTP 用户：携带系统 userId，虚拟主目录恒为 / */
    public record GfsFtpUser(String name, String gfsUserId) implements User {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getPassword() {
            return null; // 密码只在认证时用，不回传
        }

        @Override
        public int getMaxIdleTime() {
            return 300;
        }

        @Override
        public List<? extends org.apache.ftpserver.ftplet.Authority> getAuthorities(Class<? extends org.apache.ftpserver.ftplet.Authority> clazz) {
            return List.of();
        }

        @Override
        public List<? extends org.apache.ftpserver.ftplet.Authority> getAuthorities() {
            return List.of();
        }

        @Override
        public AuthorizationRequest authorize(AuthorizationRequest request) {
            return request; // 全量放行，权限由 GFS 文件层语义约束
        }

        @Override
        public String getHomeDirectory() {
            return "/";
        }

        @Override
        public boolean getEnabled() {
            return true;
        }
    }

    // ==================== 虚拟文件系统操作（供 FtpFile 桥调用） ====================

    /**
     * 解析绝对路径为目标记录（不存在返回 null；根目录返回 null）
     */
    public FileInfo resolve(String userId, String absolutePath) {
        return saTokenBridge.runAs(userId, () -> pathResolver.resolve(absolutePath, userId));
    }

    /**
     * 判断路径存在且为目录
     */
    public boolean isDirectory(String userId, String absolutePath) {
        // 根目录恒为目录
        if (GfsPathResolver.normalize(absolutePath).isEmpty()) {
            return true;
        }
        FileInfo file = resolve(userId, absolutePath);
        return file != null && Boolean.TRUE.equals(file.getIsDir());
    }

    /**
     * 判断路径存在且为文件
     */
    public boolean isFile(String userId, String absolutePath) {
        FileInfo file = resolve(userId, absolutePath);
        return file != null && !Boolean.TRUE.equals(file.getIsDir());
    }

    /**
     * 列目录（直接子项）
     */
    public List<FileInfo> listChildren(String userId, String dirAbsPath) {
        return saTokenBridge.runAs(userId, () -> {
            FileInfo dir = GfsPathResolver.normalize(dirAbsPath).isEmpty()
                    ? null : pathResolver.resolve(dirAbsPath, userId);
            String dirId = dir == null ? null : dir.getId();
            List<com.guanghe.fs.file.domain.vo.FileVO> vos = pathResolver.listChildren(dirId);
            return vos.stream().map(this::toInfo).toList();
        });
    }

    /**
     * 文件大小（不存在返回 -1）
     */
    public long fileSize(String userId, String absPath) {
        if (GfsPathResolver.normalize(absPath).isEmpty()) {
            return -1;
        }
        FileInfo file = resolve(userId, absPath);
        return file == null || file.getSize() == null ? -1L : file.getSize();
    }

    /**
     * 最后修改时间（毫秒，不存在返回 0）
     */
    public long lastModified(String userId, String absPath) {
        if (GfsPathResolver.normalize(absPath).isEmpty()) {
            return System.currentTimeMillis();
        }
        FileInfo file = resolve(userId, absPath);
        return file == null || file.getUpdateTime() == null
                ? 0L : file.getUpdateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 创建目录（父链逐级 mkdir）
     */
    public boolean mkdir(String userId, String absPath) {
        return Boolean.TRUE.equals(saTokenBridge.runAs(userId, () -> {
            String normalized = GfsPathResolver.normalize(absPath);
            if (normalized.isEmpty()) {
                return false; // 根目录已存在
            }
            String[] segments = normalized.split("/");
            FileInfo parent = null;
            StringBuilder walked = new StringBuilder();
            // 逐段找已有前缀，找到最深存在目录后逐级创建
            for (int i = 0; i < segments.length; i++) {
                String partial = walked.isEmpty() ? segments[i] : walked + "/" + segments[i];
                FileInfo existing = pathResolver.resolve(partial, userId);
                if (existing == null) {
                    // 从这里开始逐级创建
                    for (int j = i; j < segments.length; j++) {
                        CreateDirectoryCmd cmd = new CreateDirectoryCmd();
                        cmd.setFolderName(segments[j]);
                        cmd.setParentId(parent == null ? null : parent.getId());
                        parent = files().createDirectory(cmd);
                    }
                    return true;
                }
                if (!Boolean.TRUE.equals(existing.getIsDir())) {
                    return false; // 中间段是文件，无法创建
                }
                parent = existing;
                walked.append(walked.isEmpty() ? segments[i] : "/" + segments[i]);
            }
            return false; // 全部已存在
        }));
    }

    /**
     * 删除文件/目录（进回收站，继承 GFS 删除语义）
     */
    public boolean delete(String userId, String absPath) {
        return saTokenBridge.runAs(userId, () -> {
            String normalized = GfsPathResolver.normalize(absPath);
            if (normalized.isEmpty()) {
                return false;
            }
            FileInfo file = pathResolver.resolve(normalized, userId);
            if (file == null) {
                return false;
            }
            files().moveFilesToRecycleBin(List.of(file.getId()));
            return true;
        });
    }

    /**
     * 改名（同目录语义）
     */
    public boolean rename(String userId, String absPath, String newName) {
        return Boolean.TRUE.equals(saTokenBridge.runAs(userId, () -> {
            FileInfo file = pathResolver.resolve(absPath, userId);
            if (file == null) {
                return false;
            }
            RenameFileCmd cmd = new RenameFileCmd();
            cmd.setDisplayName(newName);
            files().renameFile(file.getId(), cmd);
            return true;
        }));
    }

    /**
     * 移动（跨目录改名）
     */
    public boolean move(String userId, String fromAbs, String toAbs) {
        return Boolean.TRUE.equals(saTokenBridge.runAs(userId, () -> {
            FileInfo from = pathResolver.resolve(fromAbs, userId);
            if (from == null) {
                return false;
            }
            String normalizedTo = GfsPathResolver.normalize(toAbs);
            int idx = normalizedTo.lastIndexOf('/');
            String targetParentPath = idx <= 0 ? "" : normalizedTo.substring(0, idx);
            String targetName = GfsPathResolver.baseName(normalizedTo);
            FileInfo targetParent = targetParentPath.isEmpty()
                    ? null : pathResolver.resolve(targetParentPath, userId);
            if (targetParent == null || !Boolean.TRUE.equals(targetParent.getIsDir())) {
                return false;
            }
            com.guanghe.fs.file.domain.dto.MoveFileCmd mv = new com.guanghe.fs.file.domain.dto.MoveFileCmd();
            mv.setFileIds(List.of(from.getId()));
            mv.setDirId(targetParent.getId());
            files().moveFile(mv);
            if (!targetName.equals(from.getDisplayName())) {
                RenameFileCmd rename = new RenameFileCmd();
                rename.setDisplayName(targetName);
                files().renameFile(from.getId(), rename);
            }
            return true;
        }));
    }

    /**
     * 读文件（流式，读毕由调用方关流）
     */
    public InputStream readFile(String userId, String absPath) {
        return saTokenBridge.runAs(userId, () -> {
            FileInfo file = pathResolver.resolve(absPath, userId);
            if (file == null || Boolean.TRUE.equals(file.getIsDir())) {
                return null;
            }
            // 流要在 runAs 外被消费；InputStream 不依赖 sa-token 上下文，直接返回
            return files().downloadFile(file.getId());
        });
    }

    /**
     * Range 读（start 含、end 含；断点续传）
     */
    public InputStream readFileRange(String userId, String absPath, long start, long end) {
        return saTokenBridge.runAs(userId, () -> {
            FileInfo file = pathResolver.resolve(absPath, userId);
            if (file == null || Boolean.TRUE.equals(file.getIsDir())) {
                return null;
            }
            return files().openRangeStream(file.getId(), start, end);
        });
    }

    /**
     * 写文件（spool 到临时文件，close 时提交；与 SFTP GfsWriteChannel 同策略，保证只写一次大文件不占内存）
     */
    public OutputStream writeFile(String userId, String absPath) throws IOException {
        // FtpServer 的 DataConnection 会持续 write 并在数据连接关闭时 close，
        // 这里直接给一个 spool 缓冲流，close 时提交到 FileInfoService
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("gfs-ftp-", ".spool");
        return new java.io.BufferedOutputStream(new SpoolOutputStream(userId, absPath, temp), 64 * 1024);
    }

    private com.guanghe.fs.file.domain.vo.FileVO toFileVO(FileInfo info) {
        com.guanghe.fs.file.domain.vo.FileVO vo = new com.guanghe.fs.file.domain.vo.FileVO();
        vo.setId(info.getId());
        vo.setDisplayName(info.getDisplayName());
        vo.setIsDir(info.getIsDir());
        vo.setSize(info.getSize());
        return vo;
    }

    private FileInfo toInfo(com.guanghe.fs.file.domain.vo.FileVO vo) {
        FileInfo info = new FileInfo();
        info.setId(vo.getId());
        info.setDisplayName(vo.getDisplayName());
        info.setIsDir(vo.getIsDir());
        info.setSize(vo.getSize());
        return info;
    }

    /**
     * spool 写通道：先落临时文件，close() 时提交到 GFS
     */
    private class SpoolOutputStream extends java.io.OutputStream {

        private final String userId;
        private final String absPath;
        private final java.nio.file.Path temp;
        private final java.io.OutputStream spool;
        private long count;
        private boolean closed;
        private boolean submitted;

        SpoolOutputStream(String userId, String absPath, java.nio.file.Path temp) throws IOException {
            this.userId = userId;
            this.absPath = absPath;
            this.temp = temp;
            this.spool = java.nio.file.Files.newOutputStream(temp);
        }

        @Override
        public void write(int b) throws IOException {
            spool.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            spool.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            spool.flush();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                spool.close();
                if (submitted) {
                    return;
                }
                submitted = true;
                try (InputStream in = java.nio.file.Files.newInputStream(temp)) {
                    saTokenBridge.runAs(userId, () -> {
                        String normalized = GfsPathResolver.normalize(absPath);
                        int idx = normalized.lastIndexOf('/');
                        String parentPath = idx <= 0 ? "" : normalized.substring(0, idx);
                        String name = GfsPathResolver.baseName(normalized);
                        FileInfo parent = parentPath.isEmpty()
                                ? null : pathResolver.resolve(parentPath, userId);
                        String parentId = parent == null ? null : parent.getId();
                        // 覆盖语义：同名先移入回收站
                        FileInfo existing = pathResolver.resolve(normalized, userId);
                        if (existing != null && !Boolean.TRUE.equals(existing.getIsDir())) {
                            files().moveFilesToRecycleBin(List.of(existing.getId()));
                        }
                        files().writeFileContent(parentId, name, in, count);
                        return null;
                    });
                }
                log.debug("FTP 写入提交完成: path={}, size={}", absPath, count);
            } catch (Exception e) {
                log.warn("FTP 写入提交失败: path={}", absPath, e);
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(temp);
                } catch (IOException ignore) {
                }
            }
        }
    }
}
