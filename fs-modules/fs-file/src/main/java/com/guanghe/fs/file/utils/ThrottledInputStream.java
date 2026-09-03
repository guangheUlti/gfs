package com.guanghe.fs.file.utils;

import com.guanghe.fs.framework.common.utils.I18nUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * 限速输入流包装类
 * 用于实现下载速率限制功能
 * 
 * @Author: guangheUlti
 * @Date: 2026/01/15
 */
public class ThrottledInputStream extends InputStream {
    
    private final InputStream delegate;
    private final long maxBytesPerSecond;
    private long bytesRead = 0;
    private long startTime = System.currentTimeMillis();
    
    /**
     * 创建限速输入流
     * 
     * @param delegate 原始输入流
     * @param maxBytesPerSecond 最大字节数/秒（-1 表示不限速）
     */
    public ThrottledInputStream(InputStream delegate, long maxBytesPerSecond) {
        this.delegate = delegate;
        this.maxBytesPerSecond = maxBytesPerSecond;
    }
    
    @Override
    public int read() throws IOException {
        throttle(1);
        int data = delegate.read();
        if (data != -1) {
            bytesRead++;
        }
        return data;
    }
    
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        throttle(len);
        int bytesReadNow = delegate.read(b, off, len);
        if (bytesReadNow > 0) {
            bytesRead += bytesReadNow;
        }
        return bytesReadNow;
    }
    
    /**
     * 限速控制
     * 
     * @param bytesToRead 即将读取的字节数
     */
    private void throttle(int bytesToRead) throws IOException {
        // 如果不限速，直接返回
        if (maxBytesPerSecond <= 0) {
            return;
        }
        
        // 计算已经过去的时间（毫秒）
        long elapsedTime = System.currentTimeMillis() - startTime;
        
        // 计算在当前速率下，已读取的字节数应该花费的时间（毫秒）
        long expectedTime = (bytesRead * 1000) / maxBytesPerSecond;
        
        // 如果实际时间小于预期时间，需要休眠
        if (elapsedTime < expectedTime) {
            long sleepTime = expectedTime - elapsedTime;
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(I18nUtils.getMessage("transfer.throttle.interrupted"), e);
            }
        }
    }
    
    @Override
    public void close() throws IOException {
        delegate.close();
    }
    
    @Override
    public int available() throws IOException {
        return delegate.available();
    }
    
    @Override
    public long skip(long n) throws IOException {
        long skipped = delegate.skip(n);
        bytesRead += skipped;
        return skipped;
    }
    
    @Override
    public synchronized void mark(int readlimit) {
        delegate.mark(readlimit);
    }
    
    @Override
    public synchronized void reset() throws IOException {
        delegate.reset();
    }
    
    @Override
    public boolean markSupported() {
        return delegate.markSupported();
    }
}
