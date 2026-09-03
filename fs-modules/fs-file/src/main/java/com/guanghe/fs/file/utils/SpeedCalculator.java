package com.guanghe.fs.file.utils;

import lombok.Getter;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 上传速度计算器（滑动窗口算法）
 */
@Getter
public class SpeedCalculator {
    private static class SpeedRecord {
        long timestamp;
        long bytes;

        SpeedRecord(long timestamp, long bytes) {
            this.timestamp = timestamp;
            this.bytes = bytes;
        }
    }

    private final ConcurrentLinkedQueue<SpeedRecord> records = new ConcurrentLinkedQueue<>();
    private final long windowSize = 5000; // 5秒滑动窗口
    /**
     * 获取总上传字节数
     */
    private volatile long totalUploadedBytes = 0;

    /**
     * 添加上传记录
     */
    public synchronized void addBytes(long bytes) {
        long now = System.currentTimeMillis();
        totalUploadedBytes += bytes;
        records.offer(new SpeedRecord(now, bytes));
        // 移除过期记录（超过窗口时间）
        while (!records.isEmpty()) {
            SpeedRecord oldest = records.peek();
            if (now - oldest.timestamp > windowSize) {
                records.poll();
            } else {
                break;
            }
        }
    }

    /**
     * 获取当前速度（字节/秒）
     */
    public synchronized long getSpeed() {
        if (records.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long windowBytes = 0;
        long oldestTime = now;
        for (SpeedRecord record : records) {
            windowBytes += record.bytes;
            oldestTime = Math.min(oldestTime, record.timestamp);
        }
        long duration = now - oldestTime;
        if (duration <= 0) {
            return 0;
        }
        return windowBytes * 1000 / duration;
    }

    /**
     * 计算剩余时间（秒）
     */
    public long getRemainingTime(long totalSize) {
        long speed = getSpeed();
        if (speed <= 0) {
            return 0;
        }
        long remainingBytes = totalSize - totalUploadedBytes;
        return remainingBytes / speed;
    }
}