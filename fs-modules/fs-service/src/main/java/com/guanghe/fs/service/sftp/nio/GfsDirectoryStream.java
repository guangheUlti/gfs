package com.guanghe.fs.service.sftp.nio;

import com.guanghe.fs.file.domain.vo.FileVO;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

/**
 * GFS 目录流：FileInfoService.getList(parentId) 取子项迭代。
 */
class GfsDirectoryStream implements DirectoryStream<Path> {

    private final GfsPath dir;
    private final Filter<? super Path> filter;
    private volatile boolean closed;
    private volatile Iterator<Path> iterator;

    GfsDirectoryStream(GfsPath dir, Filter<? super Path> filter) {
        this.dir = dir;
        this.filter = filter;
    }

    private void ensureIterator() throws IOException {
        if (iterator != null) {
            return;
        }
        if (closed) {
            throw new IOException("directory stream closed");
        }
        dir.getFileSystem().bridge().runAs(dir.getFileSystem().userId(), () -> {
            String parentId = null;
            try {
                if (!dir.isRoot()) {
                    com.guanghe.fs.file.domain.FileInfo info = dir.toFileInfo();
                    if (info == null || !Boolean.TRUE.equals(info.getIsDir())) {
                        throw new NotDirectoryException(dir.toString());
                    }
                    parentId = info.getId();
                }
                List<FileVO> children = dir.getFileSystem().pathResolver().listChildren(parentId);
            java.util.List<Path> paths = new java.util.ArrayList<>(children.size());
            for (FileVO vo : children) {
                Path child = dir.resolve(vo.getDisplayName());
                try {
                    if (filter == null || filter.accept(child)) {
                        paths.add(child);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            iterator = paths.iterator();
            } catch (NotDirectoryException e) {
                throw new RuntimeException(e);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(GfsDirectoryStream.class)
                        .warn("[GFS-SFTP] 目录流失败: path={}", dir, e);
                throw e;
            }
        });
        if (iterator == null) {
            iterator = java.util.List.<Path>of().iterator();
        }
    }

    @Override
    public synchronized Iterator<Path> iterator() {
        try {
            ensureIterator();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return iterator;
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
