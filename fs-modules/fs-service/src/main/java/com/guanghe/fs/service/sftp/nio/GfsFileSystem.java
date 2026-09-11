package com.guanghe.fs.service.sftp.nio;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Set;

/**
 * GFS 逻辑文件系统：每 SFTP 会话一个。
 */
public class GfsFileSystem extends FileSystem {

    private final GfsFileSystemProvider provider;
    private final String userId;
    private final GfsPath rootPath;

    GfsFileSystem(GfsFileSystemProvider provider, String userId) {
        this.provider = provider;
        this.userId = userId;
        this.rootPath = new GfsPath(this, "/");
    }

    public String userId() {
        return userId;
    }

    GfsPathResolver pathResolver() {
        return provider.resolver();
    }

    com.guanghe.fs.service.sftp.SaTokenBridge bridge() {
        return provider.bridge();
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        provider.unregisterFileSystem(this);
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return java.util.List.of(rootPath);
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return java.util.List.of();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        String joined = first;
        if (more.length > 0) {
            StringBuilder sb = new StringBuilder(first);
            for (String segment : more) {
                if (!sb.toString().endsWith("/")) {
                    sb.append('/');
                }
                sb.append(segment);
            }
            joined = sb.toString();
        }
        return new GfsPath(this, joined);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.nio.file.attribute.UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException();
    }
}
