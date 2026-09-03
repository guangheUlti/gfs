package com.guanghe.fs.file.service;

import com.mybatisflex.core.service.IService;
import com.guanghe.fs.file.domain.FileShareItem;

import java.util.List;

/**
 * 文件分享关联服务接口
 *
 * @Author: guangheUlti
 * @Date: 2025/10/30 9:35
 */
public interface FileShareItemService extends IService<FileShareItem> {

    /**
     * 创建分享文件关联
     *
     * @param shareId
     * @param fileIds
     */
    void saveShareItems(String shareId, List<String> fileIds);

    /**
     * 删除分享文件关联
     *
     * @param shareId
     */
    void removeByShareId(String shareId);

    /**
     * 获取分享对应的文件数量
     *
     * @param shareId
     * @return
     */
    Long countByShareId(String shareId);

    /**
     * 获取分享对应的文件IDS
     *
     * @param shareId
     * @return
     */
    List<String> getShareFileIds(String shareId);

    /**
     * 判断文件是否在分享中
     *
     * @param shareId
     * @param fileId
     * @return
     */
    boolean isFileInShare(String shareId, String fileId);
}
