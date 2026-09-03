package com.guanghe.fs.file.service;

import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.dto.CheckUploadCmd;
import com.guanghe.fs.file.domain.dto.InitUploadCmd;
import com.guanghe.fs.file.domain.vo.CheckUploadResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

public interface FileCollectionUploadService {
    String initUpload(String collectionId, String submissionId, String uploadToken, InitUploadCmd cmd);
    CheckUploadResultVO checkUpload(String collectionId, String submissionId, String uploadToken, CheckUploadCmd cmd);
    void uploadChunk(String collectionId, String submissionId, String uploadToken,
                     String taskId, Integer chunkIndex, String chunkMd5, MultipartFile file);
    Set<Integer> getUploadedChunks(String collectionId, String submissionId, String uploadToken, String taskId);
    FileInfo mergeChunks(String collectionId, String submissionId, String uploadToken, String taskId);
}
