package com.guanghe.fs.service.sftp.nio;

import cn.hutool.core.io.IoUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.service.FileInfoService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * GFS 写通道：本地临时文件 spool（java.io.tmpdir），仅支持顺序追加写
 * （position ≤ 当前写入位置；随机写 position 回退抛 IOException）。
 * close() 时用流式直传方法提交，随后删临时文件。
 */
@Slf4j
class GfsWriteChannel implements SeekableByteChannel {

    /** spool 大小上限（可由 config_data 覆盖，此处默认 5 GB） */
    private static final long DEFAULT_SPOOL_LIMIT = 5L * 1024 * 1024 * 1024;

    private final GfsPath path;
    private final boolean append;
    private final long spoolLimit;
    private final FileInfoService fileInfoService;
    private final Path tempFile;
    private long writePosition;
    private boolean open = true;
    private boolean submitted;

    GfsWriteChannel(GfsPath path, boolean append) throws IOException {
        this.path = path;
        this.append = append;
        this.spoolLimit = DEFAULT_SPOOL_LIMIT;
        this.fileInfoService = ((GfsFileSystemProvider) path.getFileSystem().provider()).files().getObject();
        // 已有文件走覆盖语义：非 append 从头写 = truncate 已有内容
        this.tempFile = Files.createTempFile("gfs-sftp-", ".spool");
        if (append) {
            FileInfo existing = path.toFileInfo();
            if (existing != null && !Boolean.TRUE.equals(existing.getIsDir())) {
                try (InputStream in = path.getFileSystem().bridge().runAs(path.getFileSystem().userId(),
                        () -> fileInfoService.downloadFile(existing.getId()))) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                    writePosition = existing.getSize() == null ? 0 : existing.getSize();
                }
            }
        }
    }

    @Override
    public synchronized int write(ByteBuffer src) throws IOException {
        ensureOpen();
        if (src.remaining() > 0 && writePosition + src.remaining() > spoolLimit) {
            throw new IOException("spool size limit exceeded: " + spoolLimit);
        }
        try (var out = Files.newOutputStream(tempFile, StandardOpenOption.APPEND)) {
            byte[] chunk = new byte[src.remaining()];
            src.get(chunk);
            out.write(chunk);
            writePosition += chunk.length;
            return chunk.length;
        }
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
        throw new java.nio.channels.NonWritableChannelException();
    }

    @Override
    public synchronized long position() {
        return writePosition;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        // 只允许顺序追加：position 只能等于当前写入位置
        if (newPosition != writePosition) {
            throw new IOException("random write not supported: only sequential append allowed"
                    + " (position=" + newPosition + ", current=" + writePosition + ")");
        }
        return this;
    }

    @Override
    public synchronized long size() {
        return writePosition;
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        throw new IOException("truncate not supported");
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        if (submitted) {
            return;
        }
        submitted = true;
        try {
            String targetName = GfsPathResolver.baseName(path.toString());
            FileInfo parent = path.getParent() == null ? null
                    : path.getFileSystem().bridge().runAs(path.getFileSystem().userId(),
                            () -> path.getFileSystem().pathResolver().resolve(path.getParent().toString()));
            String parentId = parent == null ? null : parent.getId();
            // 隐藏临时文件（openssh 客户端 put 过程中会重复列目录）
            try (InputStream in = Files.newInputStream(tempFile)) {
                path.getFileSystem().bridge().runAs(path.getFileSystem().userId(), () ->
                        fileInfoService.writeFileContent(parentId, targetName, in, writePosition));
            }
            log.debug("SFTP 写入提交完成: path={}, size={}", path.toString(), writePosition);
        } catch (Exception e) {
            log.warn("SFTP 写入提交失败: path={}", path.toString(), e);
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignore) {
            }
        }
    }

    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
