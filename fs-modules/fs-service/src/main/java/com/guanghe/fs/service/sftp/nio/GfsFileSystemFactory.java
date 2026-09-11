package com.guanghe.fs.service.sftp.nio;

import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.service.sftp.GfsPasswordAuthenticator;
import com.guanghe.fs.service.sftp.SaTokenBridge;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.session.SessionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;

/**
 * SFTP 会话文件系统工厂：每个 SFTP 会话一个 GfsFileSystem，
 * userId 取自认证时写入的 session attribute（GfsPasswordAuthenticator.USER_ID_KEY）。
 */
@Component
public class GfsFileSystemFactory implements FileSystemFactory {

    private final GfsFileSystemProvider provider;

    public GfsFileSystemFactory(ObjectProvider<FileInfoService> fileInfoServiceProvider,
                                SaTokenBridge saTokenBridge) {
        this.provider = new GfsFileSystemProvider(fileInfoServiceProvider,
                new GfsPathResolver(fileInfoServiceProvider), saTokenBridge);
    }

    @Override
    public Path getUserHomeDir(SessionContext session) {
        return null; // 根路径即用户根目录
    }

    @Override
    public FileSystem createFileSystem(SessionContext session) throws IOException {
        String userId = session.resolveAttribute(GfsPasswordAuthenticator.USER_ID_KEY);
        if (userId == null) {
            throw new IOException("SFTP session not authenticated");
        }
        return provider.registerFileSystem(session, userId);
    }
}
