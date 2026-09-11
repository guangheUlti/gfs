package com.guanghe.fs.service.sftp.nio;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;

/**
 * GFS 文件基本属性（size / isDirectory / lastModified=FileInfo.uploadTime / fileId）
 */
public class GfsBasicFileAttributes implements BasicFileAttributes {

    private final String fileId;
    private final Long size;
    private final LocalDateTime uploadTime;
    private final boolean directory;
    private final String path;

    GfsBasicFileAttributes(String fileId, Long size, LocalDateTime uploadTime, boolean directory, String path) {
        this.fileId = fileId;
        this.size = size;
        this.uploadTime = uploadTime;
        this.directory = directory;
        this.path = path;
    }

    public String fileId() {
        return fileId;
    }

    @Override
    public FileTime lastModifiedTime() {
        LocalDateTime t = uploadTime == null ? LocalDateTime.now() : uploadTime;
        return FileTime.from(t.atZone(java.time.ZoneId.systemDefault()).toInstant());
    }

    @Override
    public FileTime lastAccessTime() {
        return lastModifiedTime();
    }

    @Override
    public FileTime creationTime() {
        return lastModifiedTime();
    }

    @Override
    public boolean isRegularFile() {
        return !directory;
    }

    @Override
    public boolean isDirectory() {
        return directory;
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return size == null || directory ? 0 : size;
    }

    @Override
    public Object fileKey() {
        return path;
    }
}
