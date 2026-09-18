package com.guanghe.fs.service.sftp.nio;

import cn.hutool.core.io.IoUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.service.FileInfoService;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.NoSuchFileException;

/**
 * GFS 只读通道：基于 {@link FileInfoService#openRangeStream} 的真随机读。
 *
 * <p>MINA SFTP 的每个 READ 请求都带显式偏移，内部会先 {@code position(offset)} 再读。
 * 这里 position 变化时只记录目标偏移并丢弃旧流，下次 read 才按
 * {@code [position, size-1]} 字节区间开流——存储层 downloadFileRange 按需取数
 * （本地 lseek / 远程 Range 读 / 加密文件 CTR 计数器精确定位），seek 为 O(1)，
 * 满足视频播放器拖动进度条、读尾部 moov 元数据等随机读场景。</p>
 *
 * <p>顺序读（offset == 当前位置）不会重开流，整个顺序下载只建立一次存储连接。</p>
 */
class GfsReadChannel implements SeekableByteChannel {

    /** 缓冲窗口大小：减少底层随机读的系统调用/网络往返次数 */
    private static final int READ_BUFFER = 64 * 1024;

    private final GfsPath path;
    private long position;
    private boolean open = true;

    /** 当前偏移起的字节区间流（惰性打开；null = 未打开或已 EOF） */
    private InputStream current;

    /** 构造时仅校验文件存在性（OPEN 阶段快速失败），不建立存储连接 */
    GfsReadChannel(GfsPath path) throws IOException {
        this.path = path;
        this.position = 0L;
        FileInfo file = resolveFile();
        if (file == null) {
            throw new NoSuchFileException(path.getAbsolutePath());
        }
        if (Boolean.TRUE.equals(file.getIsDir())) {
            throw new IOException("is a directory: " + path.getAbsolutePath());
        }
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
        ensureOpen();
        int want = dst.remaining();
        if (want == 0) {
            return 0;
        }
        if (current == null) {
            openAt(position);
        }
        if (current == null) {
            // 起始偏移已越界（EOF），未开流
            return -1;
        }
        int total = 0;
        if (dst.hasArray()) {
            // 堆缓冲：直读进目标数组，免一次拷贝
            int off = dst.arrayOffset() + dst.position();
            while (total < want) {
                int n = current.read(dst.array(), off + total, want - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
            dst.position(dst.position() + total);
        } else {
            byte[] chunk = new byte[Math.min(want, READ_BUFFER)];
            while (total < want) {
                int n = current.read(chunk, 0, Math.min(chunk.length, want - total));
                if (n < 0) {
                    break;
                }
                dst.put(chunk, 0, n);
                total += n;
            }
        }
        position += total;
        return total > 0 ? total : -1;
    }

    /**
     * 在 from 偏移打开字节区间流（read 到 EOF）；from 越界时不打开（保持 EOF 语义）。
     * openRangeStream 内部完成鉴权与存储路由（含加密文件按 CTR 计数器重定位）。
     */
    private void openAt(long from) throws IOException {
        FileInfo file = resolveFile();
        if (file == null) {
            throw new NoSuchFileException(path.getAbsolutePath());
        }
        long size = file.getSize() == null ? 0L : file.getSize();
        if (from >= size) {
            return;
        }
        long end = size - 1;
        InputStream range = path.getFileSystem().bridge().runAs(path.getFileSystem().userId(),
                () -> files().openRangeStream(file.getId(), from, end));
        this.current = new BufferedInputStream(range, READ_BUFFER);
    }

    private FileInfo resolveFile() {
        return path.toFileInfo();
    }

    private FileInfoService files() {
        return ((GfsFileSystemProvider) path.getFileSystem().provider()).files().getObject();
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonReadableChannelException();
    }

    @Override
    public synchronized long position() {
        return position;
    }

    /**
     * 变更位置只记录偏移并丢弃旧流；下次 read 才按新偏移开流（真随机读，无 skip 丢弃）。
     * 相同位置的 position 调用保留现有流，保证顺序读不重开存储连接。
     */
    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0) {
            throw new IOException("negative position");
        }
        if (newPosition != position) {
            position = newPosition;
            IoUtil.close(current);
            current = null;
        }
        return this;
    }

    @Override
    public synchronized long size() {
        try {
            FileInfo file = resolveFile();
            return file == null || file.getSize() == null ? 0 : file.getSize();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public SeekableByteChannel truncate(long size) {
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() {
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
