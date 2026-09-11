package com.guanghe.fs.service.sftp.nio;

import cn.hutool.core.io.IoUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.service.FileInfoService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.NoSuchFileException;

/**
 * GFS 只读通道：open 时 downloadFile(fileId)；seek 用 skip 重开流（小文件足够；
 * 后续优化可换存储层 downloadFileRange）。
 */
class GfsReadChannel implements SeekableByteChannel {

    private final GfsPath path;
    private long position;
    private boolean open = true;

    GfsReadChannel(GfsPath path) throws IOException {
        this.path = path;
        openAndSkip(0);
    }

    private InputStream current;
    private long currentBase;

    private void openAndSkip(long from) throws IOException {
        IoUtil.close(current);
        current = null;
        FileInfo file = resolveFile();
        if (file == null) {
            throw new NoSuchFileException(path.getAbsolutePath());
        }
        if (Boolean.TRUE.equals(file.getIsDir())) {
            throw new IOException("is a directory: " + path.getAbsolutePath());
        }
        InputStream in = path.getFileSystem().bridge().runAs(path.getFileSystem().userId(),
                () -> files().downloadFile(file.getId()));
        if (from > 0) {
            long skipped = in.skip(from);
            if (skipped < from) {
                // skip 短跳：按字节读掉剩余
                long remaining = from - skipped;
                byte[] sink = new byte[8192];
                while (remaining > 0) {
                    long read = in.read(sink, 0, (int) Math.min(sink.length, remaining));
                    if (read < 0) {
                        break;
                    }
                    remaining -= read;
                }
            }
        }
        this.current = in;
        this.currentBase = from;
        this.position = from;
    }

    private FileInfo resolveFile() {
        return path.toFileInfo();
    }

    private FileInfoService files() {
        return ((GfsFileSystemProvider) path.getFileSystem().provider()).files().getObject();
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        ensureOpen();
        if (current == null) {
            openAndSkip(position);
        }
        if (position < currentBase || position >= currentBase + buffered()) {
            // 简化：位置总是在 currentBase 之后（我们只顺序读）；如需回退则重开
            if (position < currentBase) {
                openAndSkip(position);
            }
        }
        if (!current.markSupported()) {
            return readSlow(dst);
        }
        int want = dst.remaining();
        byte[] chunk = new byte[want];
        int total = 0;
        while (total < want) {
            int n = current.read(chunk, total, want - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        if (total <= 0) {
            return -1;
        }
        dst.put(chunk, 0, total);
        position += total;
        return total;
    }

    private long buffered() {
        // 未知剩余量，交给 EOF 信号
        return Long.MAX_VALUE / 2;
    }

    private int readSlow(ByteBuffer dst) throws IOException {
        byte[] chunk = new byte[dst.remaining()];
        int n = current.read(chunk);
        if (n > 0) {
            dst.put(chunk, 0, n);
            position += n;
        }
        return n;
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonReadableChannelException();
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0) {
            throw new IOException("negative position");
        }
        if (newPosition < currentBase || (current == null && newPosition > 0)) {
            openAndSkip(newPosition);
        } else if (newPosition > position && current != null) {
            long toSkip = newPosition - position;
            long skipped = current.skip(toSkip);
            position += skipped;
        } else {
            position = newPosition;
        }
        return this;
    }

    @Override
    public long size() {
        try {
            FileInfo file = resolveFile();
            return file == null || file.getSize() == null ? 0 : file.getSize();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public SeekableByteChannel truncate(long size) {
        throw new java.nio.channels.NonWritableChannelException();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        IoUtil.close(current);
        current = null;
    }

    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
