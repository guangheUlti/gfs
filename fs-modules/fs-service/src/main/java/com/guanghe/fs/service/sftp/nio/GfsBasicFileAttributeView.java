package com.guanghe.fs.service.sftp.nio;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * GFS basic 属性视图：MINA SftpSubsystem / java.nio.Files 通过
 * getFileAttributeView(BasicFileAttributeView.class) 读取属性的标准入口。
 * setTimes 静默忽略（GFS 元数据只读，与 WebDAV PROPPATCH 语义一致）。
 */
public class GfsBasicFileAttributeView implements BasicFileAttributeView {

    private final GfsPath path;

    GfsBasicFileAttributeView(GfsPath path) {
        this.path = path;
    }

    @Override
    public String name() {
        return "basic";
    }

    @Override
    public BasicFileAttributes readAttributes() throws IOException {
        return ((GfsFileSystemProvider) path.getFileSystem().provider())
                .readAttributes(path, GfsBasicFileAttributes.class);
    }

    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) {
        // GFS 元数据只读：忽略
    }

    @SuppressWarnings("unused")
    private static LinkOption[] noOptions() {
        return new LinkOption[0];
    }
}
