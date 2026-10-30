package com.guanghe.fs.storage.service;

import com.guanghe.fs.storage.domain.StorageSetting;
import com.guanghe.fs.storage.domain.cmd.StorageSettingAddCmd;
import com.mybatisflex.core.service.IService;
import com.guanghe.fs.storage.domain.cmd.StorageSettingEditCmd;
import com.guanghe.fs.storage.domain.vo.StorageActivePlatformsVO;
import com.guanghe.fs.storage.domain.vo.StorageFileUsageVO;
import com.guanghe.fs.storage.domain.vo.StorageSettingUserVO;

import java.util.List;

/**
 * 存储平台配置业务接口
 *
 * @Author: guangheUlti
 * @Date: 2024/10/25 14:37
 */
public interface StorageSettingService extends IService<StorageSetting> {

    /**
     * 根据用户ID获取所有存储平台配置信息
     *
     * @return
     */
    List<StorageSettingUserVO> getStorageSettingsByUser();

    /**
     * 获取所有已启用的存储平台信息
     * @return
     */
    List<StorageActivePlatformsVO> getActiveStoragePlatforms();

    /**
     * 启用或禁用配置
     *
     * @param settingId 配置ID
     * @param action    0: 禁用 1: 启用
     */
    void enableOrDisableStoragePlatform(String settingId, Integer action);

    /**
     * 新增存储平台配置信息
     *
     * @param cmd
     */
    void addStorageSetting(StorageSettingAddCmd cmd);

    /**
     * 编辑存储平台配置信息
     *
     * @param cmd
     */
    void editStorageSetting(StorageSettingEditCmd cmd);

    /**
     * 删除存储平台配置信息
     *
     * @param id         配置ID
     * @param purgeFiles 为 true 时先清理该存储下的全部文件（直读存储只清索引，托管存储连物理文件一并删除）再删除配置；
     *                   为 false 时该存储下仍有文件索引则拒绝删除
     */
    void deleteStorageSettingById(String id, boolean purgeFiles);

    /**
     * 查询存储配置的文件占用情况（删除前提示用）
     *
     * @param id 配置ID
     * @return 文件占用信息；占用统计组件未就绪时返回 null
     */
    StorageFileUsageVO getStorageFileUsage(String id);

    /**
     * 清理已下线平台的残留配置：插件已从代码移除，实例无法加载，
     * 先经挂载卸载回调清掉索引，再删除配置行。
     *
     * @param id 配置ID
     */
    void purgeOrphanPlatformSetting(String id);

    /**
     * 根据存储平台标识查询所有配置（用于检查是否有用户开通）
     *
     * @param platformIdentifier 存储平台标识
     * @return
     */
    List<StorageSetting> listByPlatformIdentifier(String platformIdentifier);
}
