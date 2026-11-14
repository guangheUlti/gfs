package com.guanghe.fs.file.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.file.domain.dto.CheckUploadCmd;
import com.guanghe.fs.file.domain.dto.InitDownloadCmd;
import com.guanghe.fs.file.domain.dto.InitUploadCmd;
import com.guanghe.fs.file.domain.dto.UploadChunkCmd;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.qry.DownloadChunkQry;
import com.guanghe.fs.file.domain.qry.TransferFilesQry;
import com.guanghe.fs.file.domain.qry.UploadConflictQry;
import com.guanghe.fs.file.domain.vo.CheckUploadResultVO;
import com.guanghe.fs.file.domain.vo.FileConflictVO;
import com.guanghe.fs.file.domain.vo.FileDownloadVO;
import com.guanghe.fs.file.domain.vo.FileTransferTaskVO;
import com.guanghe.fs.file.domain.vo.FolderDownloadTaskVO;
import com.guanghe.fs.file.domain.vo.InitDownloadResultVO;
import com.guanghe.fs.file.domain.vo.InitUploadResultVO;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.file.service.FileTransferTaskService;
import com.guanghe.fs.file.serving.FileServingPipeline;
import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.FileUtils;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.sse.SseConnectionManager;
import com.guanghe.fs.log.constant.OperationType;
import com.guanghe.fs.log.service.SysOperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 文件传输控制器
 * 
 * @author guangheUlti
 */
@Validated
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/apis/transfer")
@Tag(name = "文件传输", description = "文件传输")
public class FileTransferController {

    private final FileTransferTaskService fileTransferTaskService;
    private final FileInfoService fileInfoService;
    private final SseConnectionManager sseConnectionManager;
    private final SysOperationLogService operationLogService;
    private final FileServingPipeline servingPipeline;

    /** 一次性上传会把整块 body 读进堆算 MD5，与前端 upload-once 阈值保持一致 */
    private static final long MAX_UPLOAD_ONCE_BYTES = 64L * 1024 * 1024;

    @GetMapping("/files")
    @Operation(summary = "获取传输列表", description = "获取传输列表")
    public Result<List<FileTransferTaskVO>> getTransferFiles(TransferFilesQry qry) {
        List<FileTransferTaskVO> result = fileTransferTaskService.getTransferFiles(qry);
        return Result.ok(result);
    }

    @GetMapping("/sse")
    @Operation(summary = "建立SSE连接", description = "建立SSE连接以接收实时传输事件")
    public ResponseEntity<?> subscribe() {
        if (!StpUtil.isLogin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Result.error(HttpStatus.UNAUTHORIZED.value(), "未登录", null));
        }
        String userId = StpUtil.getLoginIdAsString();
        log.info("User {} requesting SSE connection", userId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sseConnectionManager.createConnection(userId));
    }

    @PostMapping("/init")
    @Operation(summary = "初始化文件上传", description = "初始化上传环境，返回taskId与当前存储是否支持去重（秒传）")
    public Result<InitUploadResultVO> initUpload(@RequestBody @Validated InitUploadCmd cmd) {
        InitUploadResultVO result = fileTransferTaskService.initUpload(cmd);
        return Result.ok(result, "初始化成功");
    }

    @PostMapping("/conflicts")
    @Operation(summary = "检测上传同名冲突", description = "返回目标目录中已存在、与待上传文件/目录同名的条目，供前端弹窗选择替换或保留两者")
    public Result<List<FileConflictVO>> checkUploadConflicts(@RequestBody @Validated UploadConflictQry qry) {
        return Result.ok(fileInfoService.findUploadConflicts(
                qry.getParentId(), qry.getFileNames(), qry.getDirNames()));
    }

    @PostMapping("/upload-once")
    @Operation(summary = "一次性上传小文件", description = "不超过一个分片的文件：init + 校验 + 写入 + 合并合成一次请求，MD5 由服务端计算")
    public Result<CheckUploadResultVO> uploadOnce(
            @RequestPart("file") MultipartFile file,
            @RequestParam("fileName") String fileName,
            @RequestParam("fileSize") Long fileSize,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam(value = "chunkSize", required = false) Long chunkSize,
            @RequestParam(value = "mimeType", required = false) String mimeType,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy
    ) throws Exception {
        InitUploadCmd cmd = new InitUploadCmd();
        cmd.setFileName(fileName);
        cmd.setFileSize(fileSize);
        cmd.setParentId(parentId);
        cmd.setTotalChunks(1);
        // chunkSize 缺省时不能退化成 1 字节：服务端要求分片大小在下限以上
        cmd.setChunkSize(chunkSize != null && chunkSize > 0 ? chunkSize : Math.max(1024 * 1024L, fileSize));
        cmd.setMimeType(mimeType != null && !mimeType.isBlank() ? mimeType : "application/octet-stream");
        cmd.setConflictPolicy(conflictPolicy);
        // 该路径要整块读进内存算 MD5：先按 body 大小拒绝，别等 getBytes() 把堆打满
        if (file.getSize() > MAX_UPLOAD_ONCE_BYTES) {
            throw new BusinessException(I18nUtils.getMessage("task.upload.once.too.large"));
        }
        CheckUploadResultVO result = fileTransferTaskService.uploadOnce(cmd, file.getBytes());
        return Result.ok(result, "上传完成");
    }

    @PostMapping("/check")
    @Operation(summary = "校验文件", description = "前端计算完MD5后调用，判断是否秒传")
    public Result<CheckUploadResultVO> checkUpload(@RequestBody @Validated CheckUploadCmd cmd) {
        CheckUploadResultVO result = fileTransferTaskService.checkUpload(cmd);
        return Result.ok(result);
    }

    @PostMapping("/chunk")
    @Operation(summary = "上传分片", description = "同步写入分片，落存储后返回，通过SSE推送进度")
    public Result<?> uploadChunk(
            @RequestParam("file") MultipartFile file,
            @RequestParam("taskId") String taskId,
            @RequestParam("chunkIndex") Integer chunkIndex,
            @RequestParam("chunkMd5") String chunkMd5
    ) throws Exception {
        UploadChunkCmd cmd = new UploadChunkCmd();
        cmd.setTaskId(taskId);
        cmd.setChunkIndex(chunkIndex);
        cmd.setChunkMd5(chunkMd5);
        // 分片最大 1GB，不能整块 getBytes() 进堆：直接透传 multipart 流给存储层
        try (InputStream inputStream = file.getInputStream()) {
            fileTransferTaskService.uploadChunk(inputStream, file.getSize(), cmd);
        }
        return Result.ok(null, "分片写入成功");
    }

    @PostMapping("/pause/{taskId}")
    @Operation(summary = "暂停传输")
    public Result<Void> pauseTransfer(@PathVariable String taskId) {
        fileTransferTaskService.pauseTransfer(taskId);
        return Result.ok();
    }

    @PostMapping("/resume/{taskId}")
    @Operation(summary = "继续传输")
    public Result<Void> resumeTransfer(@PathVariable String taskId) {
        fileTransferTaskService.resumeTransfer(taskId);
        return Result.ok();
    }

    @DeleteMapping("/cancel/{taskId}")
    @Operation(summary = "取消传输")
    public Result<Void> cancelUpload(@PathVariable String taskId) {
        fileTransferTaskService.cancelTransfer(taskId);
        return Result.ok();
    }

    @PostMapping("/merge/{taskId}")
    @Operation(summary = "合并分片", description = "所有分片上传完成后调用，合并分片并返回文件ID")
    public Result<String> mergeChunks(@PathVariable String taskId) {
        FileInfo fileInfo = fileTransferTaskService.mergeChunks(taskId);
        return Result.ok(fileInfo.getId(), "合并成功");
    }

    @GetMapping("/chunks/{taskId}")
    @Operation(summary = "查询已上传的分片", description = "用于断点续传，返回已上传的分片索引列表")
    public Result<Set<Integer>> getUploadedChunks(@PathVariable String taskId) {
        Set<Integer> uploadedChunks = fileTransferTaskService.getUploadedChunks(taskId);
        return Result.ok(uploadedChunks);
    }

    @DeleteMapping("/clears")
    @Operation(summary = "清空已完成的传输列表", description = "清空已完成的传输列表")
    public Result<Set<Integer>> clearTransfers() {
        fileTransferTaskService.clearTransfers();
        return Result.ok();
    }

    @PostMapping("/init-download")
    @Operation(summary = "初始化下载任务", description = "创建下载任务并返回任务信息")
    public Result<InitDownloadResultVO> initDownload(@RequestBody @Validated InitDownloadCmd cmd) {
        InitDownloadResultVO result = fileTransferTaskService.initDownload(cmd);
        return Result.ok(result, "初始化下载任务成功");
    }

    @GetMapping("/download/chunk")
    @Operation(summary = "下载分片", description = "下载指定分片，返回206 Partial Content")
    public void downloadChunk(@Validated DownloadChunkQry qry, HttpServletResponse response) throws IOException {
        com.guanghe.fs.file.domain.FileTransferTask task = fileTransferTaskService.getTask(qry.getTaskId());

        long startByte = (long) qry.getChunkIndex() * task.getChunkSize();
        long endByte = Math.min(startByte + task.getChunkSize() - 1, task.getFileSize() - 1);
        long contentLength = endByte - startByte + 1;

        // 必须在请求线程内打开分片流：限速读取依赖 Sa-Token 请求上下文，
        // 若放入 StreamingResponseBody 会在异步线程执行并丢失上下文
        try (InputStream inputStream = fileTransferTaskService.downloadChunk(
                qry.getTaskId(), qry.getChunkIndex())) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    String.format("bytes %d-%d/%d", startByte, endByte, task.getFileSize()));
            response.setContentLengthLong(contentLength);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + URLEncoder.encode(task.getFileName(), StandardCharsets.UTF_8) + "\"");
            IOUtils.copy(inputStream, response.getOutputStream());
            response.getOutputStream().flush();
        }
    }

    @GetMapping("/download/chunks/{taskId}")
    @Operation(summary = "查询已下载分片", description = "获取已下载的分片索引列表")
    public Result<Set<Integer>> getDownloadedChunks(@PathVariable String taskId) {
        Set<Integer> downloadedChunks = fileTransferTaskService.getDownloadedChunks(taskId);
        return Result.ok(downloadedChunks);
    }

    @GetMapping("/download/{fileId}")
    @Operation(summary = "下载文件", description = "根据文件ID下载文件")
    @Parameter(name = "fileId", description = "文件ID", in = ParameterIn.PATH, required = true)
    public ResponseEntity<Resource> downloadFile(@Parameter(description = "文件ID") @PathVariable("fileId") String fileId) {

        try {
            // 获取文件信息和文件流
            FileDownloadVO fileDownload = fileTransferTaskService.downloadFile(fileId);

            operationLogService.recordSuccess(
                    OperationType.DOWNLOAD,
                    "下载文件",
                    "FILE",
                    fileId,
                    fileDownload.getFileName(),
                    "文件大小: " + FileUtils.formatFileSize(fileDownload.getFileSize())
            );

            // 设置响应头
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + URLEncoder.encode(fileDownload.getFileName(), StandardCharsets.UTF_8) + "\"");
            headers.add(HttpHeaders.CONTENT_TYPE, "application/octet-stream");

            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(fileDownload.getFileSize())
                    .body(fileDownload.getResource());
        } catch (Exception e) {
            throw new RuntimeException("文件下载失败", e);
        }
    }

    @PostMapping("/folder-download/tasks/{folderId}")
    @Operation(summary = "创建文件夹下载任务", description = "异步打包文件夹，并返回打包进度")
    public Result<FolderDownloadTaskVO> createFolderDownloadTask(@PathVariable String folderId) {
        FolderDownloadTaskVO result = fileTransferTaskService.createFolderDownloadTask(folderId);
        return Result.ok(result, "文件夹下载任务已创建");
    }

    @PostMapping("/batch-download/tasks")
    @Operation(summary = "创建批量下载任务", description = "异步打包多个文件/文件夹并返回进度")
    public Result<FolderDownloadTaskVO> createBatchDownloadTask(@RequestBody @Validated List<String> ids) {
        FolderDownloadTaskVO result = fileTransferTaskService.createBatchDownloadTask(ids);
        return Result.ok(result, "批量下载任务已创建");
    }

    @GetMapping("/folder-download/tasks/{taskId}")
    @Operation(summary = "查询文件夹下载任务进度", description = "查询文件夹打包进度")
    public Result<FolderDownloadTaskVO> getFolderDownloadTask(@PathVariable String taskId) {
        FolderDownloadTaskVO result = fileTransferTaskService.getFolderDownloadTask(taskId);
        return Result.ok(result);
    }

    @DeleteMapping("/folder-download/tasks/{taskId}")
    @Operation(summary = "取消文件夹下载打包任务", description = "取消正在进行的文件夹打包并清理临时文件")
    public Result<Void> cancelFolderDownloadTask(@PathVariable String taskId) {
        fileTransferTaskService.cancelFolderDownloadTask(taskId);
        return Result.ok();
    }

    @GetMapping("/folder-download/tasks/{taskId}/file")
    @Operation(summary = "下载文件夹压缩包", description = "下载已打包完成的文件夹 zip")
    public ResponseEntity<StreamingResponseBody> downloadFolderTaskFile(
            @PathVariable String taskId,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        FileDownloadVO fileDownload = fileTransferTaskService.downloadFolderTaskFile(taskId);
        long size = fileDownload.getFileSize() == null ? -1 : fileDownload.getFileSize();
        FileServingPipeline.Request request = FileServingPipeline.Request
                .attachment(fileDownload.getFileName(), size,
                        (start, end) -> fileDownload.getResource().getInputStream())
                // zip 附件：范围交付无意义，统一全量
                .withRangeAllowed(false);
        return servingPipeline.serve(request, rangeHeader);
    }
}
