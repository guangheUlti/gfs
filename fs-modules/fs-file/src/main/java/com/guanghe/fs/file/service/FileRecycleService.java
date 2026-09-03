package com.guanghe.fs.file.service;

import com.guanghe.fs.file.domain.qry.FileRecycleQry;
import com.guanghe.fs.file.domain.vo.FileRecycleVO;
import com.guanghe.fs.framework.common.domain.PageResult;

import java.util.List;

/**
 * 文件回收站服务接口
 *
 * @Author: guangheUlti
 * @Date: 2025/5/8 9:35
 */
public interface FileRecycleService {

    /**
     * 分页查询回收站文件列表
     *
     * @param qry 查询参数
     * @return 分页结果
     */
    PageResult<FileRecycleVO> getRecyclePages(FileRecycleQry qry);

    /**
     * 恢复已删除的文件
     *
     * @param fileIds 文件ID集合
     * @return
     */
    void restoreFiles(List<String> fileIds);

    /**
     * 永久删除文件
     *
     * @param fileIds 文件ID集合
     * @return
     */
    void permanentlyDeleteFiles(List<String> fileIds);

    /**
     * 清空回收站
     */
    void clearRecycles();
}
