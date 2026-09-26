package com.guanghe.fs.storage.plugin.memory;

import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.chunk.AbstractTempChunkStorageService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;
import com.guanghe.fs.storage.plugin.memory.config.MemoryConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存挂载插件（Memory）
 * <p>
 * 在服务器内存中开辟一块受容量硬上限约束的区域当网盘：文件不入数据库索引
 * （能力位 {@code isDirectAccess=true}，fs-file 侧浏览时按层实时对账）、
 * 不落盘，每次增删改查都直接作用于内存目录树。
 * <p>
 * 存储介质（堆外，按需增长）：
 * <ul>
 *   <li>文件内容存放在 JVM 堆外（{@link ByteBuffer#allocateDirect(int)}，按 1 MiB 分块），
 *       不占用堆内存、无需为内容调大 {@code -Xmx}，常规业务堆压力与内容总量无关；</li>
 *   <li>按需增长：不预分配，写入多少分配多少；上传以 64 KiB 堆内 scratch 中转拷入堆外块，
 *       堆内瞬时占用与文件大小无关；单文件大小不受 {@code byte[]} 2GB 限制；</li>
 *   <li>容量记账以「文件内容字节数」为准（仅文件，目录不占额），写入前硬校验
 *       {@code 已用 + 新增 <= 容量}，超限拒绝并提示可释放空间，全程持写锁；</li>
 *   <li>删除/覆盖后旧块不再被引用，由 GC Cleaner 回收堆外内存；</li>
 *   <li>堆外总量同时受 {@code -XX:MaxDirectMemorySize}（默认≈{@code -Xmx}）约束，
 *       超限抛 {@code OutOfMemoryError: Direct buffer memory}，部署时按实例容量之和预留；</li>
 *   <li>安全红线：键强制 posix、禁 {@code ..} 逃逸；分片复用基类 temp 方案
 *       （分片落 {@code java.io.tmpdir}，complete 合并后流式进堆外，合并过程不占大块堆）。</li>
 * </ul>
 * 生命周期：实例删除或服务重启后整棵树随实例回收，内容即释放。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/26
 */
@Slf4j
@StoragePlugin(
        identifier = "Memory",
        name = "内存挂载",
        description = "在服务器内存中开辟一块指定大小的区域直接挂进网盘：文件不落盘、不建后台索引，浏览时实时列目录，增删改查直接读写内存，实例删除或服务重启后内容即释放。",
        icon = "icon-bendicunchu1",
        schemaResource = "classpath:schema/memory-schema.json"
)
public class MemoryStorageOperationService extends AbstractTempChunkStorageService {

    /** schema 容量上限（MB）：防御性兜底，前端与后端双重校验；真实约束仍是 JVM 堆外配额与物理内存 */
    private static final long MAX_CAPACITY_MB = 65536L;

    /** 堆外块大小（1 MiB）：文件内容按此粒度分块存放 */
    private static final int BLOCK_SIZE = 1024 * 1024;

    /** 上传中转 scratch（堆内，常量小buffer，与文件大小无关） */
    private static final int SCRATCH_SIZE = 64 * 1024;

    /** 内存目录树：posix 相对键 -> 节点（TreeMap 按键自然序） */
    private final TreeMap<String, Node> tree = new TreeMap<>();

    /** 树读写锁：列举/对账走读锁，写入/删除/容量记账走写锁 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 已用容量（字节）：仅统计文件内容，目录不占额 */
    private long usedBytes = 0L;

    /** 容量上限（字节） */
    private long capacityBytes;

    public MemoryStorageOperationService() {
        super();
    }

    public MemoryStorageOperationService(StorageConfig config) {
        super(config);
    }

    @Override
    protected void validateConfig(StorageConfig config) {
        MemoryConfig cfg = readConfig(config);
        long capacityMb = cfg.parsedCapacityMb();
        if (capacityMb <= 0) {
            throw new StorageConfigException("内存挂载配置错误：内存大小必须是正整数（MB）");
        }
        if (capacityMb > MAX_CAPACITY_MB) {
            throw new StorageConfigException("内存挂载配置错误：内存大小不能超过 " + MAX_CAPACITY_MB + " MB");
        }
    }

    @Override
    protected void initialize(StorageConfig config) {
        MemoryConfig cfg = readConfig(config);
        this.capacityBytes = cfg.parsedCapacityMb() * 1024L * 1024L;
        log.info("{} 内存挂载初始化完成: capacityMb={}, mountName={}, media=off-heap(1MiB blocks)",
                getLogPrefix(), cfg.parsedCapacityMb(), cfg.getMountName());
    }

    private MemoryConfig readConfig(StorageConfig config) {
        try {
            return MemoryConfig.toObject(config);
        } catch (Exception e) {
            throw new StorageConfigException("内存挂载配置解析失败: " + e.getMessage());
        }
    }

    // ==================== 读写实现 ====================

    @Override
    public void uploadFile(InputStream inputStream, String objectKey) {
        ensureNotPrototype();
        String key = normalizeKey(objectKey);
        if (key.isEmpty() || key.contains("\\") || key.contains("..")) {
            throw new StorageOperationException("非法的内存挂载路径: " + objectKey);
        }
        lock.writeLock().lock();
        try {
            Node old = tree.get(key);
            long oldBytes = (old != null && !old.dir) ? old.size : 0L;
            long freeBytes = capacityBytes - usedBytes + oldBytes;

            // 流式写入堆外块：按需分配，超限即刻失败（已分配块丢弃，由 GC Cleaner 回收）
            List<ByteBuffer> blocks = new ArrayList<>();
            long written = 0L;
            ByteBuffer block = ByteBuffer.allocateDirect(BLOCK_SIZE);
            byte[] scratch = new byte[SCRATCH_SIZE];
            try {
                int n;
                while ((n = inputStream.read(scratch)) != -1) {
                    int off = 0;
                    while (off < n) {
                        int put = Math.min(n - off, block.remaining());
                        block.put(scratch, off, put);
                        off += put;
                        if (!block.hasRemaining()) {
                            ensureCapacity(freeBytes, written + BLOCK_SIZE);
                            written += BLOCK_SIZE;
                            block.flip();
                            blocks.add(block);
                            block = ByteBuffer.allocateDirect(BLOCK_SIZE);
                        }
                    }
                }
                if (block.position() > 0) {
                    int tail = block.position();
                    ensureCapacity(freeBytes, written + tail);
                    written += tail;
                    block.flip();
                    blocks.add(block);
                }
            } catch (StorageOperationException e) {
                throw e;
            } catch (IOException e) {
                log.error("{} 文件上传失败（读取输入流）: objectKey={}", getLogPrefix(), objectKey, e);
                throw new StorageOperationException("内存挂载写入失败: " + e.getMessage(), e);
            }

            // 结构放置（父链创建；冲突抛出时 blocks 丢弃即可，尚未记账）
            placeNode(key, new Node(false, List.copyOf(blocks), written, System.currentTimeMillis()));
            usedBytes += written - oldBytes;
            log.debug("{} 文件上传成功: objectKey={}, bytes={}", getLogPrefix(), objectKey, written);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public InputStream downloadFile(String objectKey) {
        ensureNotPrototype();
        Node node = readFileNode(objectKey);
        return new BlockInputStream(viewBlocks(node.blocks, 0, node.size), node.size);
    }

    @Override
    public InputStream downloadFileRange(String objectKey, long startByte, long endByte) {
        ensureNotPrototype();
        if (startByte < 0 || endByte < startByte) {
            throw new StorageOperationException("无效的字节范围: startByte=" + startByte + ", endByte=" + endByte);
        }
        Node node = readFileNode(objectKey);
        if (startByte >= node.size) {
            throw new StorageOperationException("起始字节超出文件大小: startByte=" + startByte + ", fileSize=" + node.size);
        }
        long length = Math.min(endByte - startByte + 1, node.size - startByte);
        return new BlockInputStream(viewBlocks(node.blocks, startByte, length), length);
    }

    @Override
    public void deleteFile(String objectKey) {
        ensureNotPrototype();
        lock.writeLock().lock();
        try {
            String key = normalizeKey(objectKey);
            Node removed = tree.remove(key);
            if (removed != null && !removed.dir) {
                usedBytes -= removed.size;
            }
            log.debug("{} 文件删除成功: objectKey={}", getLogPrefix(), objectKey);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void rename(String objectKey, String destObjectKey) {
        ensureNotPrototype();
        lock.writeLock().lock();
        try {
            String from = normalizeKey(objectKey);
            String to = normalizeKey(destObjectKey);
            if (from.equals(to)) {
                return;
            }
            Node node = tree.get(from);
            if (node == null) {
                throw new StorageOperationException("源文件不存在: " + objectKey);
            }
            // 目录改名/移动：连同全部子孙键一起前缀搬运（保留内容与 mtime；纯改名不新增内容，无需容量校验）
            List<Map.Entry<String, Node>> subtree = new ArrayList<>();
            if (node.dir) {
                String prefix = from + "/";
                for (Map.Entry<String, Node> e : tree.tailMap(prefix).entrySet()) {
                    if (!e.getKey().startsWith(prefix)) {
                        break;
                    }
                    subtree.add(Map.entry(e.getKey(), e.getValue()));
                }
            }
            tree.remove(from);
            for (Map.Entry<String, Node> e : subtree) {
                tree.remove(e.getKey());
            }
            placeNode(to, node);
            String toPrefix = to + "/";
            for (Map.Entry<String, Node> e : subtree) {
                String childRel = e.getKey().substring(from.length() + 1);
                tree.put(toPrefix + childRel, e.getValue());
            }
            log.debug("{} 重命名成功: {} -> {}", getLogPrefix(), objectKey, destObjectKey);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String getFileUrl(String objectKey, Integer expireSeconds) {
        throw new StorageOperationException("内存挂载不支持生成公网直链");
    }

    @Override
    public InputStream getFileStream(String objectKey) {
        return downloadFile(objectKey);
    }

    @Override
    public boolean isFileExist(String objectKey) {
        ensureNotPrototype();
        lock.readLock().lock();
        try {
            return tree.containsKey(normalizeKey(objectKey));
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 挂载/直读能力位 ====================

    @Override
    public boolean isMountMode() {
        return true;
    }

    @Override
    public boolean isDirectAccess() {
        return true;
    }

    @Override
    public boolean isRealtimeWatchSupported() {
        return false;
    }

    @Override
    public boolean supportsWatchRealtime() {
        return false;
    }

    // ==================== 目录操作（挂载同步器契约） ====================

    @Override
    public void mkdirDirectory(String dirKey) {
        ensureNotPrototype();
        lock.writeLock().lock();
        try {
            placeNode(dirKey, new Node(true));
            log.debug("{} 创建目录成功: dirKey={}", getLogPrefix(), dirKey);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<StorageObjectEntry> listObjects(String dirKey) {
        ensureNotPrototype();
        lock.readLock().lock();
        try {
            String parentKey = normalizeKey(dirKey);
            Node dir = tree.get(parentKey);
            if (parentKey.isEmpty()) {
                dir = new Node(true); // 根恒存在
            }
            if (dir == null || !dir.dir) {
                throw new StorageOperationException("目录不存在: " + parentKey);
            }
            String prefix = parentKey.isEmpty() ? "" : parentKey + "/";
            List<StorageObjectEntry> entries = new ArrayList<>();
            // TreeMap 字典序：同前缀连续区段即一层子条目，前缀后无 '/' 的第一个分量即子名
            for (Map.Entry<String, Node> e : tree.tailMap(prefix).entrySet()) {
                String key = e.getKey();
                if (!key.startsWith(prefix)) {
                    break;
                }
                String rest = key.substring(prefix.length());
                if (rest.isEmpty() || rest.contains("/")) {
                    continue; // 自身或更深层级，跳过
                }
                Node node = e.getValue();
                entries.add(new StorageObjectEntry(
                        key,
                        node.dir,
                        node.dir ? null : node.size,
                        node.mtime));
            }
            return entries;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void deleteDirectory(String dirKey) {
        ensureNotPrototype();
        lock.writeLock().lock();
        try {
            String key = normalizeKey(dirKey);
            Node node = tree.get(key);
            if (node == null) {
                return; // 幂等
            }
            List<String> removedKeys = new ArrayList<>();
            if (node.dir && !key.isEmpty()) {
                String prefix = key + "/";
                for (String k : tree.tailMap(prefix).keySet()) {
                    if (!k.startsWith(prefix)) {
                        break;
                    }
                    removedKeys.add(k);
                }
            }
            removedKeys.add(key);
            long freed = 0L;
            for (String k : removedKeys) {
                Node removed = tree.remove(k);
                if (removed != null && !removed.dir) {
                    freed += removed.size;
                }
            }
            usedBytes -= freed;
            log.info("{} 目录删除成功: dirKey={}, entries={}, freedBytes={}",
                    getLogPrefix(), dirKey, removedKeys.size(), freed);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== 容量/生命周期 ====================

    @Override
    public Long getAvailableSpace() {
        ensureNotPrototype();
        lock.readLock().lock();
        try {
            return Math.max(0L, capacityBytes - usedBytes);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 当前环境还可申请的最大配置容量（可在原型上调用，无实例依赖）：
     * 取「JVM 堆外余量」与「schema 单实例上限」的较小值。
     * <p>
     * 堆外余量 = MaxDirectMemorySize（未显式配置时 JDK 默认取最大堆）- 当前已用直接内存；
     * 这是保守估计：未计入未来其它实例/其它组件的堆外占用。
     */
    @Override
    public Long getMaxConfigurableCapacity() {
        long schemaMax = MAX_CAPACITY_MB * 1024L * 1024L;
        try {
            long maxDirect = jvmMaxDirectMemory();
            if (maxDirect > 0) {
                long usedDirect = jvmUsedDirectMemory();
                long freeDirect = Math.max(0L, maxDirect - usedDirect);
                return Math.min(schemaMax, freeDirect);
            }
        } catch (Exception e) {
            log.debug("读取 JVM 堆外内存信息失败，仅按 schema 上限提示: {}", e.getMessage());
        }
        return schemaMax;
    }

    /**
     * 堆外上限（字节）：优先读启动参数 -XX:MaxDirectMemorySize；
     * 未显式配置时按 JDK 默认规则取最大堆上限（受支持的 MXBean，无模块访问问题）
     */
    private static long jvmMaxDirectMemory() {
        for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            String a = arg.trim();
            if (a.startsWith("-XX:MaxDirectMemorySize=")) {
                return parseJvmSize(a.substring("-XX:MaxDirectMemorySize=".length()));
            }
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    }

    /** 当前已用直接内存（字节）：标准 BufferPoolMXBean（受支持 API） */
    private static long jvmUsedDirectMemory() {
        for (java.lang.management.BufferPoolMXBean pool : java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                return pool.getMemoryUsed();
            }
        }
        return -1L;
    }

    /** 解析 JVM 参数容量值（支持 1024 / 512k / 256m / 4g 等写法，K/M/G 大小写不敏感） */
    private static long parseJvmSize(String raw) {
        try {
            String v = raw.trim().toLowerCase();
            long mult = 1L;
            if (v.endsWith("g")) {
                mult = 1024L * 1024 * 1024;
                v = v.substring(0, v.length() - 1);
            } else if (v.endsWith("m")) {
                mult = 1024L * 1024;
                v = v.substring(0, v.length() - 1);
            } else if (v.endsWith("k")) {
                mult = 1024L;
                v = v.substring(0, v.length() - 1);
            }
            return Long.parseLong(v.trim()) * mult;
        } catch (Exception e) {
            return -1L;
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            long freed = usedBytes;
            int count = tree.size();
            tree.clear();
            usedBytes = 0L;
            log.info("{} 内存挂载已释放: entries={}, freedBytes={}（堆外块由 GC Cleaner 回收）",
                    getLogPrefix(), count, freed);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== 分片上传（基类契约） ====================

    /**
     * 分片临时根目录：分片先落 {@code java.io.tmpdir}（不占内存挂载配额），complete 合并后流式进堆外并计容量
     */
    @Override
    protected String getTempRoot() {
        return resolveTempRoot(null, "memory");
    }

    /**
     * 合并完成后把临时文件流式写入堆外（uploadFile 语义：边流边分配堆外块 + 容量校验，合并不占大块堆）
     */
    @Override
    protected void writeMerged(java.nio.file.Path mergedFile, String objectKey) {
        try (InputStream in = java.nio.file.Files.newInputStream(mergedFile)) {
            uploadFile(in, objectKey);
        } catch (IOException e) {
            throw new StorageOperationException("内存挂载合并写入失败: " + objectKey + ", " + e.getMessage(), e);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 容量硬校验：freeBytes 为本次写入前的可用余量（已折算覆盖写差额），needed 为即将新增字节数
     */
    private void ensureCapacity(long freeBytes, long needed) {
        if (needed > freeBytes) {
            long needMb = (needed + 1023) / 1024 / 1024;
            long freeMb = Math.max(0L, freeBytes) / 1024 / 1024;
            throw new StorageOperationException(String.format(
                    "内存挂载空间不足：需要 %d MB，剩余 %d MB，请先清理不需要的文件", needMb, freeMb));
        }
    }

    /**
     * 结构放置：逐级创建父目录链（不参与容量记账，记账由调用方负责）
     */
    private void placeNode(String objectKey, Node node) {
        String key = normalizeKey(objectKey);
        if (key.isEmpty() || key.contains("\\") || key.contains("..")) {
            throw new StorageOperationException("非法的内存挂载路径: " + objectKey);
        }
        int idx = key.indexOf('/');
        while (idx > 0) {
            String parent = key.substring(0, idx);
            Node existing = tree.get(parent);
            if (existing == null) {
                tree.put(parent, new Node(true));
            } else if (!existing.dir) {
                throw new StorageOperationException("父路径已是文件: " + parent);
            }
            idx = key.indexOf('/', idx + 1);
        }
        tree.put(key, node);
    }

    /**
     * 读取文件节点（持读锁快照引用；块内容写入后不可变，读流可安全跨锁使用）
     */
    private Node readFileNode(String objectKey) {
        lock.readLock().lock();
        try {
            Node node = resolveNode(objectKey);
            if (node == null || node.dir) {
                throw new StorageOperationException("文件不存在: " + objectKey);
            }
            return node;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 解析节点（normalize + 逃逸校验）
     */
    private Node resolveNode(String objectKey) {
        String key = normalizeKey(objectKey);
        if (key.contains("\\") || key.contains("..")) {
            throw new StorageOperationException("非法的内存挂载路径: " + key);
        }
        return tree.get(key);
    }

    /**
     * 生成只读块视图序列：从 offset 起共 length 字节。
     * duplicate() 共享底层堆外内存、位置独立；块内容写入后不可变，视图可跨锁安全读取。
     */
    private static List<ByteBuffer> viewBlocks(List<ByteBuffer> blocks, long offset, long length) {
        List<ByteBuffer> views = new ArrayList<>();
        if (length <= 0 || blocks == null || blocks.isEmpty()) {
            return views;
        }
        int blockIdx = (int) (offset / BLOCK_SIZE);
        int offsetInBlock = (int) (offset % BLOCK_SIZE);
        for (int i = blockIdx; i < blocks.size() && length > 0; i++) {
            ByteBuffer dup = blocks.get(i).duplicate();
            if (i == blockIdx) {
                dup.position(offsetInBlock);
            }
            int take = (int) Math.min(dup.remaining(), length);
            dup.limit(dup.position() + take);
            views.add(dup);
            length -= take;
        }
        return views;
    }

    /**
     * 键归一化：与 LocalMount 同规则（去首尾 '/'、posix）。
     * 注意不能复用 LocalMount 的包内方法，本插件自带实现。
     */
    private static String normalizeKey(String objectKey) {
        String key = objectKey == null ? "" : objectKey.trim();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    /**
     * 内存节点：目录无内容；文件持堆外块列表（写入后不可变）与总字节数
     */
    private static final class Node {
        final boolean dir;
        final List<ByteBuffer> blocks;
        final long size;
        final long mtime;

        Node(boolean dir, List<ByteBuffer> blocks, long size, long mtime) {
            this.dir = dir;
            this.blocks = blocks;
            this.size = size;
            this.mtime = mtime;
        }

        Node(boolean dir) {
            this(dir, null, 0L, 0L);
        }
    }

    /**
     * 跨块顺序读流：基于只读块视图（duplicate），不拷贝内容、不持锁
     */
    private static final class BlockInputStream extends InputStream {
        private final Iterator<ByteBuffer> it;
        private ByteBuffer current;
        private long remaining;

        BlockInputStream(List<ByteBuffer> views, long total) {
            this.it = views.iterator();
            this.remaining = total;
            this.current = it.hasNext() ? it.next() : null;
        }

        private boolean advanceIfExhausted() {
            while (remaining > 0 && (current == null || !current.hasRemaining())) {
                current = it.hasNext() ? it.next() : null;
                if (current == null) {
                    remaining = 0;
                    return false;
                }
            }
            return remaining > 0;
        }

        @Override
        public int read() {
            if (!advanceIfExhausted()) {
                return -1;
            }
            remaining--;
            return current.get() & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (!advanceIfExhausted()) {
                return -1;
            }
            int total = 0;
            while (len > 0 && remaining > 0) {
                if (!current.hasRemaining()) {
                    current = it.hasNext() ? it.next() : null;
                    if (current == null) {
                        remaining = 0;
                        break;
                    }
                    continue;
                }
                int n = Math.min(len, current.remaining());
                n = (int) Math.min(n, remaining);
                current.get(b, off, n);
                off += n;
                len -= n;
                total += n;
                remaining -= n;
            }
            return total > 0 ? total : -1;
        }

        @Override
        public int available() {
            if (remaining <= 0) {
                return 0;
            }
            long avail = Math.min(remaining, current == null ? 0 : current.remaining());
            return (int) Math.min(avail, Integer.MAX_VALUE);
        }
    }
}
