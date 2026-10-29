package com.guanghe.fs.file.service;

import com.mybatisflex.core.service.IService;
import com.guanghe.fs.file.domain.FileCollection;
import com.guanghe.fs.file.domain.dto.CreateFileCollectionCmd;
import com.guanghe.fs.file.domain.dto.CreateFileCollectionSubmissionCmd;
import com.guanghe.fs.file.domain.qry.FileCollectionQry;
import com.guanghe.fs.file.domain.qry.FileCollectionSubmissionQry;
import com.guanghe.fs.file.domain.vo.FileCollectionPublicVO;
import com.guanghe.fs.file.domain.vo.FileCollectionSubmissionSessionVO;
import com.guanghe.fs.file.domain.vo.FileCollectionSubmissionVO;
import com.guanghe.fs.file.domain.vo.FileCollectionUploadContext;
import com.guanghe.fs.file.domain.vo.FileCollectionDeletionResult;
import com.guanghe.fs.file.domain.vo.FileCollectionVO;
import com.guanghe.fs.file.enums.FileCollectionStatus;
import com.guanghe.fs.framework.common.domain.PageResult;

public interface FileCollectionService extends IService<FileCollection> {
    PageResult<FileCollectionVO> getPages(FileCollectionQry qry);
    FileCollectionVO getDetail(String collectionId);
    FileCollectionVO createCollection(CreateFileCollectionCmd cmd);
    FileCollectionVO updateStatus(String collectionId, FileCollectionStatus status);
    FileCollectionDeletionResult deleteCollection(String collectionId);
    PageResult<FileCollectionSubmissionVO> getSubmissions(String collectionId, FileCollectionSubmissionQry qry);
    FileCollectionPublicVO getPublicInfo(String collectionId);
    FileCollectionSubmissionSessionVO startSubmission(String collectionId, CreateFileCollectionSubmissionCmd cmd);
    void completeSubmission(String collectionId, String submissionId, String uploadToken);
    FileCollectionUploadContext authorizeUpload(String collectionId, String submissionId, String uploadToken);
    void recordUploadedFile(String collectionId, String submissionId, long fileSize);

    /**
     * 删除指定用户的全部文件收集（含提交记录与已结束的收集任务），供管理员删除账号使用
     *
     * @param userId 用户id
     * @return 删除的收集数量
     */
    int purgeUserCollections(String userId);
}
