package com.guanghe.fs.file.service;

import com.guanghe.fs.file.domain.FileUserFavorites;
import com.mybatisflex.core.service.IService;

import java.util.Collection;
import java.util.List;

/**
 * 用户收藏文件服务接口
 *
 * @Author: guangheUlti
 * @Date: 2025/5/12 13:49
 */
public interface FileUserFavoritesService extends IService<FileUserFavorites> {

    /**
     * 收藏文件
     *
     * @param fileIds 文件ID集合
     * @return 是否收藏成功
     */
    void favoritesFile(List<String> fileIds);

    /**
     * 取消收藏文件
     *
     * @param fileIds 文件ID集合
     * @return 是否取消收藏成功
     */
    void unFavoritesFile(List<String> fileIds);

    /**
     * 根据文件ID批量删除收藏记录
     *
     * @param fileIds 文件ID列表
     * @param userId  用户ID
     */
    void removeByFileIds(Collection<String> fileIds, String userId);

    /**
     * 根据文件ID批量删除所有用户的收藏记录（用于定时清理等场景）
     */
    void removeByFileIds(Collection<String> fileIds);

    /**
     * 获取用户收藏文件数量
     *
     * @return
     */
    Long getFavoritesCount();
}
