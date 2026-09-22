package com.guanghe.fs.file.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.dto.CopyFileCmd;
import com.guanghe.fs.file.domain.dto.CreateDirectoryCmd;
import com.guanghe.fs.file.domain.dto.CreateTextFileCmd;
import com.guanghe.fs.file.domain.dto.MoveFileCmd;
import com.guanghe.fs.file.domain.dto.RenameFileCmd;
import com.guanghe.fs.file.domain.dto.UpdateTextContentCmd;
import com.guanghe.fs.file.domain.qry.FileQry;
import com.guanghe.fs.file.domain.qry.FileRecycleQry;
import com.guanghe.fs.file.domain.vo.FileDetailVO;
import com.guanghe.fs.file.domain.vo.FileVO;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.file.service.FileRecycleService;
import com.guanghe.fs.file.service.FileUserFavoritesService;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.log.constant.OperationType;
import com.guanghe.fs.log.service.SysOperationLogService;
import com.guanghe.fs.system.constant.FeatureKeys;
import com.guanghe.fs.system.service.SysFeatureToggleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件资源控制器
 *
 * @Author: guangheUlti
 * @Date: 2025/5/8 10:00
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/apis/file")
@Tag(name = "文件管理", description = "文件上传、下载、管理等接口")
public class FileController {

    @Autowired
    private FileInfoService fileInfoService;

    @Autowired
    private FileRecycleService fileRecycleService;

    @Autowired
    private FileUserFavoritesService fileUserFavoritesService;

    @Autowired
    private SysOperationLogService operationLogService;

    @Autowired
    private SysFeatureToggleService featureToggleService;

    @GetMapping("/list")
    @Operation(summary = "查询所有文件列表", description = "支持关键词搜索和文件类型筛选的列表查询")
    public PageResult<FileVO> getList(FileQry qry) {
        return fileInfoService.getList(qry);
    }

    @GetMapping("/{fileId}")
    @Operation(summary = "查询文件详情", description = "查询文件详情")
    public Result<FileDetailVO> getFileDetails(@PathVariable String fileId) {
        FileDetailVO details = fileInfoService.getFileDetails(fileId);
        return Result.ok(details);
    }

    @GetMapping("/dirs")
    @Operation(summary = "查询目录列表", description = "查询目录列表")
    public Result<List<FileVO>> getDirs(String parentId) {
        List<FileVO> list = fileInfoService.getDirs(parentId);
        return Result.ok(list);
    }

    @GetMapping("/url/{fileId}")
    @Operation(summary = "获取文件URL", description = "获取文件的访问URL")
    @Parameters(value = {@Parameter(name = "fileId", description = "文件ID", required = true), @Parameter(name = "expireSeconds", description = "URL有效时间（秒），如果不支持或永久有效可为null")})
    public Result<String> getFileUrl(@PathVariable("fileId") String fileId, @RequestParam(value = "expireSeconds", required = false) Integer expireSeconds) {

        String url = fileInfoService.getFileUrl(fileId, expireSeconds);
        return Result.ok(url);
    }

    @DeleteMapping()
    @Operation(summary = "删除文件", description = "回收站开启时移入回收站；关闭时直接物理删除（不可恢复）")
    @SaCheckPermission("file:write")
    public Result<?> deleteFiles(@RequestBody List<String> fileIds) {
        List<FileInfo> targets = getAuthorizedFiles(fileIds);
        boolean recycleEnabled = featureToggleService.listToggles()
                .getOrDefault(FeatureKeys.RECYCLE_BIN, Boolean.TRUE);
        if (recycleEnabled) {
            fileInfoService.moveFilesToRecycleBin(fileIds);
            operationLogService.recordSuccess(
                    OperationType.DELETE,
                    "放入回收站",
                    targetType(targets),
                    String.join(",", fileIds),
                    summarizeNames(targets),
                    "共 " + targets.size() + " 项"
            );
        } else {
            // 回收站关闭：删除即永久删除，不产生回收站记录
            fileRecycleService.permanentlyDeleteActiveFiles(fileIds);
            operationLogService.recordSuccess(
                    OperationType.PERMANENT_DELETE,
                    "删除（回收站已关闭，直接物理删除）",
                    targetType(targets),
                    String.join(",", fileIds),
                    summarizeNames(targets),
                    "共 " + targets.size() + " 项"
            );
        }
        return Result.ok();
    }

    @DeleteMapping("/permanent")
    @Operation(summary = "永久删除文件", description = "不经回收站直接永久删除文件，不可恢复")
    @SaCheckPermission("file:write")
    public Result<?> permanentlyDeleteActiveFiles(@RequestBody List<String> fileIds) {
        List<FileInfo> targets = getAuthorizedFiles(fileIds);
        fileRecycleService.permanentlyDeleteActiveFiles(fileIds);
        operationLogService.recordSuccess(
                OperationType.PERMANENT_DELETE,
                "永久删除",
                targetType(targets),
                String.join(",", fileIds),
                summarizeNames(targets),
                "共 " + targets.size() + " 项"
        );
        return Result.ok();
    }

    @PostMapping("/directory")
    @Operation(summary = "创建目录", description = "在指定目录下创建新目录")
    @SaCheckPermission("file:write")
    public Result<FileInfo> createDirectory(@RequestBody @Validated CreateDirectoryCmd cmd) {
        FileInfo fileInfo = fileInfoService.createDirectory(cmd);
        operationLogService.recordSuccess(
                OperationType.CREATE_FOLDER,
                "新建文件夹",
                "DIRECTORY",
                fileInfo.getId(),
                fileInfo.getDisplayName(),
                "父目录: " + (fileInfo.getParentId() == null ? "根目录" : fileInfo.getParentId())
        );
        return Result.ok(fileInfo);
    }

    @PostMapping("/text")
    @Operation(summary = "新建纯文本文件", description = "在指定目录下新建 .txt 文本文件")
    @SaCheckPermission("file:write")
    public Result<?> createTextFile(@RequestBody @Validated CreateTextFileCmd cmd) {
        FileInfo fileInfo = fileInfoService.createTextFile(cmd);
        operationLogService.recordSuccess(
                OperationType.CREATE_TEXT,
                "新建纯文本",
                "FILE",
                fileInfo.getId(),
                fileInfo.getDisplayName(),
                "父目录: " + (fileInfo.getParentId() == null ? "根目录" : fileInfo.getParentId())
        );
        // 与 copies 一致，不返回完整实体，避免暴露对象存储键等内部字段
        return Result.ok();
    }

    @GetMapping("/{fileId}/content")
    @Operation(summary = "读取文本内容", description = "读取 .txt 文件的内容用于在线编辑")
    public Result<String> readTextContent(@PathVariable String fileId) {
        return Result.ok(fileInfoService.readTextContent(fileId));
    }

    @PutMapping("/{fileId}/content")
    @Operation(summary = "保存文本内容", description = "保存在线编辑后的 .txt 文件内容")
    @SaCheckPermission("file:write")
    public Result<?> updateTextContent(@PathVariable String fileId,
                                       @RequestBody @Validated UpdateTextContentCmd cmd) {
        fileInfoService.updateTextContent(fileId, cmd);
        operationLogService.recordSuccess(
                OperationType.EDIT_TEXT,
                "在线编辑文本",
                "FILE",
                fileId,
                fileInfoService.getAuthorizedFile(fileId).getDisplayName(),
                null
        );
        return Result.ok();
    }

    @PutMapping("/{fileId}/rename")
    @Operation(summary = "文件重命名", description = "文件重命名")
    @SaCheckPermission("file:write")
    public Result<?> createDirectory(@PathVariable String fileId, @RequestBody @Validated RenameFileCmd cmd) {
        FileInfo before = fileInfoService.getAuthorizedFile(fileId);
        String oldName = before.getDisplayName();
        fileInfoService.renameFile(fileId, cmd);
        FileInfo after = fileInfoService.getAuthorizedFile(fileId);
        operationLogService.recordSuccess(
                OperationType.RENAME,
                "重命名",
                Boolean.TRUE.equals(after.getIsDir()) ? "DIRECTORY" : "FILE",
                fileId,
                after.getDisplayName(),
                oldName + " -> " + after.getDisplayName()
        );
        return Result.ok();
    }

    @PutMapping("/moves")
    @Operation(summary = "文件移动", description = "文件移动")
    @SaCheckPermission("file:write")
    public Result<?> createDirectory(@RequestBody @Validated MoveFileCmd cmd) {
        List<FileInfo> targets = getAuthorizedFiles(cmd.getFileIds());
        String targetName = "根目录";
        if (cmd.getDirId() != null && !cmd.getDirId().isBlank()) {
            targetName = fileInfoService.getAuthorizedFile(cmd.getDirId()).getDisplayName();
        }
        fileInfoService.moveFile(cmd);
        operationLogService.recordSuccess(
                OperationType.MOVE,
                "移动文件",
                targetType(targets),
                String.join(",", cmd.getFileIds()),
                summarizeNames(targets),
                "移动到: " + targetName
        );
        return Result.ok();
    }

    @PostMapping("/copies")
    @Operation(summary = "复制文件", description = "复制文件或文件夹到指定目录")
    @SaCheckPermission("file:write")
    public Result<?> copyFiles(@RequestBody @Validated CopyFileCmd cmd) {
        List<FileInfo> sources = getAuthorizedFiles(cmd.getFileIds());
        List<FileInfo> copies = fileInfoService.copyFiles(cmd);
        String targetName = "根目录";
        if (cmd.getDirId() != null && !cmd.getDirId().isBlank()) {
            targetName = fileInfoService.getAuthorizedFile(cmd.getDirId()).getDisplayName();
        }
        operationLogService.recordSuccess(
                OperationType.COPY,
                "复制文件",
                targetType(sources),
                copies.stream().map(FileInfo::getId).collect(Collectors.joining(",")),
                summarizeNames(copies),
                "从 " + summarizeNames(sources) + " 复制到: " + targetName
        );
        // 不返回完整文件实体，避免把对象存储键等内部字段暴露给客户端。
        return Result.ok();
    }

    @GetMapping("/directory/{dirId}/path")
    @Operation(summary = "获取目录层级", description = "根据目录ID获取目录层级")
    public Result<List<FileVO>> createDirectory(@PathVariable String dirId) {
        List<FileVO> fileVOS = fileInfoService.getDirectoryTreePath(dirId);
        return Result.ok(fileVOS);
    }

    @GetMapping("/recycle/pages")
    @Operation(summary = "分页获取回收站列表", description = "分页获取回收站列表")
    public PageResult<?> getRecyclePages(FileRecycleQry qry) {
        return fileRecycleService.getRecyclePages(qry);
    }

    @PutMapping("/recycles")
    @Operation(summary = "恢复文件", description = "从回收站批量恢复文件")
    @SaCheckPermission("file:write")
    public Result<?> restoreFile(@RequestBody List<String> fileIds) {
        fileRecycleService.restoreFiles(fileIds);
        operationLogService.recordSuccess(
                OperationType.RESTORE,
                "还原文件",
                fileIds.size() > 1 ? "MULTIPLE" : "FILE",
                String.join(",", fileIds),
                null,
                "共还原 " + fileIds.size() + " 项"
        );
        return Result.ok();
    }

    @DeleteMapping("/recycles")
    @Operation(summary = "永久删除文件", description = "永久删除文件，不可恢复")
    @SaCheckPermission("file:write")
    public Result<?> permanentlyDeleteFiles(@RequestBody List<String> fileIds) {
        fileRecycleService.permanentlyDeleteFiles(fileIds);
        operationLogService.recordSuccess(
                OperationType.PERMANENT_DELETE,
                "彻底删除",
                fileIds.size() > 1 ? "MULTIPLE" : "FILE",
                String.join(",", fileIds),
                null,
                "共彻底删除 " + fileIds.size() + " 项"
        );
        return Result.ok();
    }

    @DeleteMapping("/recycles/clear")
    @Operation(summary = "清空回收站", description = "清空回收站，永久删除所有文件")
    @SaCheckPermission("file:write")
    public Result<?> clearRecycles() {
        fileRecycleService.clearRecycles();
        operationLogService.recordSuccess(
                OperationType.CLEAR_RECYCLE,
                "清空回收站",
                "RECYCLE_BIN",
                null,
                "回收站",
                null
        );
        return Result.ok();
    }

    @PostMapping("/favorites")
    @Operation(summary = "收藏文件", description = "收藏文件")
    @SaCheckPermission("file:write")
    public Result<?> favoritesFile(@RequestBody List<String> fileIds) {
        fileUserFavoritesService.favoritesFile(fileIds);
        return Result.ok();
    }

    @DeleteMapping("/favorites")
    @Operation(summary = "取消收藏文件", description = "取消收藏文件")
    @SaCheckPermission("file:write")
    public Result<?> unFavoritesFile(@RequestBody List<String> fileIds) {
        fileUserFavoritesService.unFavoritesFile(fileIds);
        return Result.ok();
    }

    private List<FileInfo> getAuthorizedFiles(List<String> fileIds) {
        return fileIds.stream()
                .distinct()
                .map(fileInfoService::getAuthorizedFile)
                .toList();
    }

    private String summarizeNames(List<FileInfo> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        String names = files.stream()
                .limit(5)
                .map(FileInfo::getDisplayName)
                .collect(Collectors.joining("、"));
        return files.size() > 5 ? names + " 等 " + files.size() + " 项" : names;
    }

    private String targetType(List<FileInfo> files) {
        if (files.size() != 1) {
            return "MULTIPLE";
        }
        return Boolean.TRUE.equals(files.getFirst().getIsDir()) ? "DIRECTORY" : "FILE";
    }
}
