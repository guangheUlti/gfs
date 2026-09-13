package com.guanghe.fs.file.service;

import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.dto.CreateDirectoryCmd;
import com.guanghe.fs.file.domain.dto.CopyFileCmd;
import com.guanghe.fs.file.domain.dto.CreateTextFileCmd;
import com.guanghe.fs.file.domain.dto.MoveFileCmd;
import com.guanghe.fs.file.domain.dto.RenameFileCmd;
import com.guanghe.fs.file.domain.dto.UpdateTextContentCmd;
import com.guanghe.fs.file.domain.qry.FileQry;
import com.mybatisflex.core.service.IService;
import com.guanghe.fs.file.domain.vo.FileDetailVO;
import com.guanghe.fs.file.domain.vo.FileVO;
import com.guanghe.fs.framework.common.domain.PageResult;

import java.io.InputStream;
import java.util.List;

/**
 * 文件资源服务接口
 *
 * @Author: guangheUlti
 * @Date: 2025/5/8 9:35
 */
public interface FileInfoService extends IService<FileInfo> {

    /**
     * 获取当前登录用户可访问的文件。
     */
    FileInfo getAuthorizedFile(String fileId);

    /**
     * 统计指定存储配置下的文件数量（含各级目录），用于删除存储配置前的占用检查
     */
    long countByStorageSettingId(String settingId);

    /**
     * 下载文件
     *
     * @param fileId 文件ID
     * @return 文件输入流
     */
    InputStream downloadFile(String fileId);

    /**
     * 获取文件访问URL
     *
     * @param fileId        文件ID
     * @param expireSeconds URL有效时间（秒），如果不支持或永久有效可为null或0
     * @return 文件访问URL
     */
    String getFileUrl(String fileId, Integer expireSeconds);

    /**
     * 放入回收站
     *
     * @param fileIds 文件ID集合
     * @return 是否删除成功
     */
    void moveFilesToRecycleBin(List<String> fileIds);

    /**
     * 创建目录
     *
     * @param cmd 创建目录请求参数
     * @return
     */
    FileInfo createDirectory(CreateDirectoryCmd cmd);

    /**
     * 新建纯文本文件（.txt）
     *
     * @param cmd 新建文本请求参数
     * @return 新建的文件记录
     */
    FileInfo createTextFile(CreateTextFileCmd cmd);

    /**
     * 读取文本文件内容
     *
     * @param fileId 文件ID
     * @return 文本内容
     */
    String readTextContent(String fileId);

    /**
     * 更新文本文件内容（生成/复用新物理对象后切换引用）
     *
     * @param fileId 文件ID
     * @param cmd    更新文本请求参数
     */
    void updateTextContent(String fileId, UpdateTextContentCmd cmd);

    /**
     * 生成唯一的文件名（处理重名冲突）
     * <p>
     * - 如果不存在重名：返回原名称
     * - 如果存在重名：自动添加 (1), (2), (3)... 后缀
     *
     * @param userId                   用户ID
     * @param parentId                 父目录ID
     * @param desiredName              期望的文件名
     * @param isDir                    是否是文件夹
     * @param excludeFileId            排除的文件ID（可选，用于重命名场景）
     * @param storagePlatformSettingId 存储平台设置ID
     * @return 唯一的文件名
     */
    String generateUniqueName(String userId, String parentId,
                              String desiredName, Boolean isDir,
                              String excludeFileId, String storagePlatformSettingId);

    /**
     * 重命名文件
     *
     * @param fileId 文件ID
     * @param cmd    重命名请求参数
     */
    void renameFile(String fileId, RenameFileCmd cmd);

    /**
     * 移动文件到指定目录
     *
     * @param cmd 移动文件请求参数
     */
    void moveFile(MoveFileCmd cmd);

    /**
     * 复制文件或文件夹到指定目录。
     * 复制只新增数据库引用，不重复上传物理对象。
     */
    List<FileInfo> copyFiles(CopyFileCmd cmd);

    /**
     * 获取目录层级
     *
     * @param dirId 目录ID
     * @return
     */
    List<FileVO> getDirectoryTreePath(String dirId);

    /**
     * 查询文件列表
     *
     * @param qry 查询参数（包含关键词、文件类型、分页参数等）
     * @return 分页结果
     */
    PageResult<FileVO> getList(FileQry qry);

    /**
     * 计算当前存储平台已使用的存储空间（全系统口径，不区分用户）
     *
     * @return 已使用字节数
     */
    Long calculateUsedStorage();

    /**
     * 查询文件详情
     *
     * @param fileId 文件ID
     * @return
     */
    FileDetailVO getFileDetails(String fileId);

    /**
     * 根据父目录ID查询目录列表
     *
     * @param parentId
     * @return
     */
    List<FileVO> getDirs(String parentId);

    /**
     * 根据文件ID列表查询文件信息
     *
     * @param fileIds
     * @return
     */
    List<FileVO> getByFileIds(List<String> fileIds);

    /**
     * 流式直传：目标存在同名文件时覆盖（写新物理对象→切换引用→清理旧对象），不存在则新建记录。
     * 供 WebDAV PUT 与 SFTP 写等协议端共用；不使用 generateUniqueName（协议端重名语义 = 覆盖）。
     *
     * @param parentId    父目录ID（空 = 用户根目录）
     * @param displayName 目标文件名
     * @param in          内容输入流
     * @param size        期望字节数（未知传 null）
     * @return 落库后的文件记录
     */
    FileInfo writeFileContent(String parentId, String displayName, InputStream in, Long size);
}
