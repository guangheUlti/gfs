package com.guanghe.fs.file.controller;

import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.preview.ArchiveFilePreviewService;
import com.guanghe.fs.file.serving.FileServingPipeline;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.config.FilePreviewConfig;
import com.guanghe.fs.framework.preview.core.PreviewStrategy;
import com.guanghe.fs.framework.preview.factory.PreviewStrategyManager;
import com.guanghe.fs.framework.preview.strategy.impl.archive.ArchiveUtil;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * 文件流控制器：媒体/预览流交付。
 * <p>
 * 策略分发与 Range 兼容性判断留在本类（业务语义），HTTP 交付段
 * （头构建/Range 解析/流拷贝）统一走 {@link FileServingPipeline}。
 *
 * @author guangheUlti
 */
@Slf4j
@RestController
@RequestMapping("/api/file/stream")
@RequiredArgsConstructor
public class FileStreamController {

    private final FileInfoService fileInfoService;
    private final StorageServiceFacade storageServiceFacade;
    private final FilePreviewConfig previewConfig;
    private final PreviewStrategyManager strategyManager;
    private final ArchiveFilePreviewService archiveFilePreviewService;
    private final FileServingPipeline servingPipeline;

    @GetMapping("/preview/{fileId}")
    public ResponseEntity<StreamingResponseBody> preview(
            @PathVariable String fileId,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {

        FileInfo fileInfo = fileInfoService.getById(fileId);
        if (fileInfo == null) {
            return ResponseEntity.notFound().build();
        }

        IStorageOperationService storage = storageServiceFacade
                .getStorageService(fileInfo.getStoragePlatformSettingId());

        FileTypeEnum fileType = FileTypeEnum.fromFileName(fileInfo.getDisplayName());
        PreviewStrategy strategy = strategyManager.getStrategy(fileType);

        log.info("文件: {}, 类型: {}, 匹配策略: {}", fileInfo.getDisplayName(), fileType, strategy.getClass().getSimpleName());

        String responseExt = strategy.getResponseExtension(fileInfo.getSuffix());

        FileServingPipeline.Request request = FileServingPipeline.Request
                // 策略支持 Range 才允许；转换流（docx→pdf）强制全量防截断
                .inline(fileInfo.getDisplayName(), fileInfo.getSize() == null ? -1 : fileInfo.getSize(),
                        (start, end) -> openStream(storage, strategy, fileInfo, start, end))
                .withRangeAllowed(strategy.supportRange() && strategy.needConvert() == false)
                .withMaxRangeSize(previewConfig.getMaxRangeSize())
                .withResponseExtension(responseExt)
                // inline 媒体可缓存一周（与原 buildHeaders 语义一致）
                .withCacheControl(strategy.supportRange() ? "public, max-age=604800" : "no-cache");

        return servingPipeline.serve(request, rangeHeader);
    }

    /**
     * 打开源流：Range 走 downloadFileRange（存储侧裁剪），全量走 getFileStream + 策略加工
     */
    private InputStream openStream(IStorageOperationService storage, PreviewStrategy strategy,
                                   FileInfo file, long start, long end) throws Exception {
        if (start > 0 || (end > 0 && file.getSize() != null && end < file.getSize() - 1)) {
            // Range：存储层原生定位（明文 seek / 加密 CTR 重定位 / 远程 Range 读），
            // 已是最终字节，不再过策略加工
            return storage.downloadFileRange(file.getObjectKey(), start, end);
        }
        InputStream source = storage.getFileStream(file.getObjectKey());
        return strategy.processStream(source, file.getSuffix());
    }

    /**
     * 获取压缩包内文件流（内存缓存，全量交付；转换类型不设 Content-Length 的语义由管道
     * 的 size=-1 传导：无长度则不设 Content-Length）
     */
    @GetMapping("/preview/archive/inner/{tempId}")
    public ResponseEntity<StreamingResponseBody> previewArchiveInner(
            @PathVariable String tempId,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        log.info("获取压缩包内文件流: tempId={}", tempId);

        byte[] fileContent = archiveFilePreviewService.getCachedInnerFile(tempId);
        if (fileContent == null) {
            log.warn("压缩包内文件缓存已过期或不存在: tempId={}", tempId);
            return ResponseEntity.notFound().build();
        }

        String displayName = archiveFilePreviewService.getCachedInnerFileName(tempId);
        if (displayName == null || displayName.isBlank()) {
            displayName = "file.bin";
        }

        String suffix = ArchiveUtil.getExtension(displayName).toLowerCase(Locale.ROOT);
        FileTypeEnum fileType = FileTypeEnum.fromFileName(displayName);
        PreviewStrategy strategy = strategyManager.getStrategy(fileType);

        // 转换流长度未知 → size=-1（管道不设 Content-Length）；非转换流 = 字节数组长度
        long visibleSize = strategy.needConvert() ? -1 : fileContent.length;

        FileServingPipeline.Request request = FileServingPipeline.Request
                .inline(displayName, visibleSize,
                        (start, end) -> {
                            InputStream source = new ByteArrayInputStream(fileContent);
                            return strategy.needConvert()
                                    ? strategy.processStream(source, suffix) : source;
                        })
                .withRangeAllowed(false)
                .withResponseExtension(strategy.getResponseExtension(suffix))
                .withCacheControl(strategy.supportRange() ? "public, max-age=604800" : "no-cache");

        return servingPipeline.serve(request, rangeHeader);
    }
}
