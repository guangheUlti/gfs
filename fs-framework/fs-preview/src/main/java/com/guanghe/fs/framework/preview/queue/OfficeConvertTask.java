package com.guanghe.fs.framework.preview.queue;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

@Data
@AllArgsConstructor
public class OfficeConvertTask {
    private InputStream sourceStream;
    private String sourceExtension;
    private CompletableFuture<InputStream> future;
    /** 转换逻辑挂在任务上，避免与 worker 闭包错配 */
    private BiFunction<InputStream, String, InputStream> converter;
}
